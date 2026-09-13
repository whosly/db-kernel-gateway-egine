package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLCommandType;
import com.whosly.gateway.adapter.mysql.MySQLFrameCodec;
import com.whosly.gateway.adapter.mysql.MySQLTestFrames;
import com.whosly.gateway.adapter.mysql.MySQLTextRow;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the adapter wiring: masking happens through a whole proxy session when a
 * rule is registered, and nothing at all is touched when none is.
 *
 * <p>The relay-level test shows the pipeline works; this shows the adapter actually
 * installs it — and, just as importantly, that an empty registry leaves a
 * deployment's bytes exactly as they were.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
class MySqlProtocolAdapterMaskingTest {

    private static final int HEADER_LENGTH = MySQLFrameCodec.HEADER_LENGTH;
    private static final int INT4_TYPE = 0x08;
    private static final int VARCHAR_TYPE = 0x0F;
    /** A minimal client handshake response: no TLS, no deprecate-EOF. */
    private static final byte[] HANDSHAKE_RESPONSE =
            new byte[]{0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

    @Test
    void masksRowsThroughTheAdapterWhenARuleIsRegistered() throws Exception {
        byte[] row = runSession(engineWithRule());

        List<byte[]> values = valuesOf(row);
        assertThat(new String(values.get(0), StandardCharsets.US_ASCII)).isEqualTo("7");
        assertThat(values.get(1)).isNull();
    }

    @Test
    void forwardsRowsUntouchedWhenNoRuleIsRegistered() throws Exception {
        byte[] row = runSession(MaskingEngine.inactive());

        // Byte for byte: with an empty registry the rewriting path is switched off.
        assertThat(row).isEqualTo(MySQLTestFrames.packet(MySQLTestFrames.rowPayload("7", "alice@example.com"), 5));
    }

    /**
     * Runs one session through a real adapter: handshake, query, and a two-column
     * result set whose second column a rule may claim.
     *
     * @return the row packet as the client received it
     */
    private static byte[] runSession(MaskingEngine engine) throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             MySQLTestFrames.SocketPair client = MySQLTestFrames.SocketPair.open()) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());
            adapter.setMaskingEngine(engine);

            Future<?> adapterFuture = executor.submit(() -> adapter.handleClientConnection(client.serverSide()));
            Future<?> backendFuture = executor.submit(() -> serveResultSet(targetServer));
            try {
                InputStream fromAdapter = client.clientSide().getInputStream();
                MySQLTestFrames.readPacket(fromAdapter);                     // target handshake
                client.clientSide().getOutputStream().write(HANDSHAKE_RESPONSE);
                client.clientSide().getOutputStream().flush();
                MySQLTestFrames.readPacket(fromAdapter);                     // OK: session becomes ready

                client.clientSide().getOutputStream()
                        .write(MySQLTestFrames.commandPacket(MySQLCommandType.COM_QUERY));
                client.clientSide().getOutputStream().flush();

                for (int sequenceId = 1; sequenceId <= 4; sequenceId++) {
                    MySQLTestFrames.readPacket(fromAdapter);
                }
                byte[] row = MySQLTestFrames.readPacket(fromAdapter);

                backendFuture.get(2, TimeUnit.SECONDS);
                client.clientSide().close();
                adapterFuture.get(2, TimeUnit.SECONDS);
                return row;
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void serveResultSet(ServerSocket targetServer) {
        try (Socket targetSocket = targetServer.accept()) {
            OutputStream toClient = targetSocket.getOutputStream();
            toClient.write(MySQLTestFrames.packet(handshakePayload(), 0));
            toClient.flush();
            MySQLTestFrames.readExact(targetSocket.getInputStream(), HANDSHAKE_RESPONSE.length);

            // A target OK packet is what moves the observer into the command phase.
            toClient.write(MySQLTestFrames.packet(new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00}, 2));
            toClient.flush();

            MySQLTestFrames.readPacket(targetSocket.getInputStream());       // the forwarded query

            toClient.write(MySQLTestFrames.packet(new byte[]{0x02}, 1));
            toClient.write(MySQLTestFrames.packet(MySQLTestFrames.columnPayload(
                    "id", INT4_TYPE, MySQLTestFrames.UTF8_COLLATION), 2));
            toClient.write(MySQLTestFrames.packet(MySQLTestFrames.columnPayload(
                    "email", VARCHAR_TYPE, MySQLTestFrames.UTF8_COLLATION), 3));
            toClient.write(MySQLTestFrames.columnDefinitionsTerminator(4));
            toClient.write(MySQLTestFrames.packet(MySQLTestFrames.rowPayload("7", "alice@example.com"), 5));
            toClient.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] handshakePayload() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x0A);                                                 // protocol version 10
        payload.writeBytes("8.0.0".getBytes(StandardCharsets.US_ASCII));
        payload.write(0);
        return payload.toByteArray();
    }

    private static List<byte[]> valuesOf(byte[] row) {
        return MySQLTextRow.parse(row, HEADER_LENGTH, row.length - HEADER_LENGTH).orElseThrow();
    }

    private static MaskingEngine engineWithRule() {
        return new MaskingEngine(new MaskingRuleRegistry(
                List.of(new NullingRule("null-email", 10, ColumnSelector.named("email")))));
    }
}
