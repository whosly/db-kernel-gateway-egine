package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficInspector;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.adapter.protocol.LoopbackSockets;
import com.whosly.gateway.adapter.protocol.MessagePipeline;
import com.whosly.gateway.adapter.protocol.ProtocolErrorResponder;
import com.whosly.gateway.adapter.protocol.RewriteLimits;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import com.whosly.gateway.masking.NullingRule;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.StatementClassifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the PostgreSQL masking path end to end, through a real relay and with the
 * bytes a client would receive.
 *
 * <p>Two things only this level can show:</p>
 * <ul>
 *   <li>the session starts in the startup family, where the protocol layer offers no
 *       framing at all, and becomes framed later — a bounder captured once must still
 *       follow that change;</li>
 *   <li>the {@code RowDescription} reaches the client byte for byte while the
 *       {@code DataRow} behind it is rewritten.</li>
 * </ul>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
class PostgreSQLResultSetMaskingRelayTest {

    private static final PostgreSQLFrameCodec CODEC = new PostgreSQLFrameCodec();
    private static final int TYPED_HEADER_LENGTH = PostgreSQLFrameCodec.TYPED_HEADER_LENGTH;
    private static final int PROTOCOL_VERSION_3 = 196608;

    @Test
    void masksARowAfterTheSessionBecomesFramed() throws Exception {
        List<List<byte[]>> rows = runSession(1);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(1)).isNull();
    }

    @Test
    void masksEveryRowWhenSeveralArriveInOneRead() throws Exception {
        List<List<byte[]>> rows = runSession(2);

        // Both rows are separate messages, so both are masked rather than refused:
        // handing two messages over as one is what a rewrite must never be given.
        assertThat(rows).hasSize(2);
        rows.forEach(row -> assertThat(row.get(1)).isNull());
    }

    /**
     * Plays a frontend and a database through a real relay, and returns the rows the
     * frontend received for the result set.
     */
    private static List<List<byte[]>> runSession(int rowCount) throws Exception {
        try (LoopbackSockets.Pair frontend = LoopbackSockets.open();
             LoopbackSockets.Pair database = LoopbackSockets.open()) {
            /*
             * A missing packet must fail the test instead of blocking it forever: an
             * end-to-end test that hangs tells nobody which exchange went wrong.
             */
            frontend.clientSide().setSoTimeout(3000);
            database.clientSide().setSoTimeout(3000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> relayFuture = startRelay(executor, frontend, database);

                // Frontend: a startup message, then a query.
                byte[] startup = startupMessage("user", "postgres", "database", "shop");
                frontend.clientSide().getOutputStream().write(startup);
                frontend.clientSide().getOutputStream().flush();
                byte[] forwardedStartup =
                        LoopbackSockets.readExact(database.clientSide().getInputStream(), startup.length);
                assertThat(forwardedStartup).isEqualTo(startup);

                // Database: authentication, ready, and the result set.
                OutputStream toRelay = database.clientSide().getOutputStream();
                toRelay.write(CODEC.packet('R', int4(0)));                    // AuthenticationOk
                toRelay.write(readyForQuery());
                toRelay.flush();

                byte[] query = CODEC.packet('Q', "select id, email from accounts\0"
                        .getBytes(StandardCharsets.US_ASCII));
                frontend.clientSide().getOutputStream().write(query);
                frontend.clientSide().getOutputStream().flush();
                assertThat(LoopbackSockets.readExact(database.clientSide().getInputStream(), query.length))
                        .isEqualTo(query);

                byte[] rowDescription = CODEC.packet('T', PostgreSQLColumnMetadata.payload(List.of(
                        new PostgreSQLColumnMetadata.Field("id", 0, PostgreSQLTypeOid.INT4.getOid(), 0),
                        new PostgreSQLColumnMetadata.Field("email", 0, PostgreSQLTypeOid.VARCHAR.getOid(), 0))));
                toRelay.write(rowDescription);
                for (int index = 0; index < rowCount; index++) {
                    toRelay.write(CODEC.packet('D', PostgreSQLDataRow.encode(
                            Arrays.asList(bytes("7"), bytes("alice@example.com")))));
                }
                toRelay.write(CODEC.packet('C', "SELECT 2\0".getBytes(StandardCharsets.US_ASCII)));
                toRelay.write(readyForQuery());
                toRelay.flush();

                InputStream fromRelay = frontend.clientSide().getInputStream();
                readPacket(fromRelay);                                          // AuthenticationOk
                readPacket(fromRelay);                                          // ReadyForQuery
                assertThat(readPacket(fromRelay)).isEqualTo(rowDescription);     // untouched

                List<List<byte[]>> rows = new ArrayList<>();
                for (int index = 0; index < rowCount; index++) {
                    byte[] row = readPacket(fromRelay);
                    assertThat(row[0]).isEqualTo((byte) 'D');
                    List<byte[]> values = PostgreSQLDataRow.parse(row, TYPED_HEADER_LENGTH,
                            row.length - TYPED_HEADER_LENGTH).orElseThrow();
                    assertThat(new String(values.get(0), StandardCharsets.US_ASCII)).isEqualTo("7");
                    rows.add(values);
                }
                readPacket(fromRelay);                                          // CommandComplete
                readPacket(fromRelay);                                          // ReadyForQuery

                frontend.clientSide().close();
                database.clientSide().close();
                relayFuture.get(2, TimeUnit.SECONDS);
                return List.copyOf(rows);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static Future<?> startRelay(ExecutorService executor, LoopbackSockets.Pair frontend,
                                        LoopbackSockets.Pair database) {
        String sessionId = "pg-mask-relay";
        PostgreSQLSession session = new PostgreSQLSession(sessionId);
        /*
         * The session starts before the startup message is consumed, which is the phase
         * where no boundary can be trusted yet: the bounder is handed out here and must
         * still follow the later change.
         */
        PostgreSQLDatabaseEventExtractor extractor =
                new PostgreSQLDatabaseEventExtractor("PostgreSQL", sessionId, false, session);
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(
                List.of(new NullingRule("null-email", 10, ColumnSelector.named("email")))));
        DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                extractor::inspect,
                DatabaseTrafficObserver.noop(),
                DatabaseRiskPolicy.allowAll(),
                session,
                new StatementClassifier(new DruidSqlParser()));
        MessagePipeline pipeline = MessagePipeline.of(inspector,
                new PostgreSQLResultSetMaskingInterceptor(extractor, engine));
        DuplexRelay relay = new DuplexRelay(sessionId, pipeline,
                new ProtocolErrorResponder(CODEC, new PostgreSQLProtocolErrorMapper()),
                RewriteLimits.defaults());

        return executor.submit(() -> {
            try {
                relay.relay(frontend.serverSide(), database.serverSide());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Reads one typed message, header included. */
    private static byte[] readPacket(InputStream inputStream) throws Exception {
        byte[] header = LoopbackSockets.readExact(inputStream, TYPED_HEADER_LENGTH);
        int payloadLength = PostgreSQLFrameCodec.readInt4(header, 1, TYPED_HEADER_LENGTH)
                - PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH;
        byte[] payload = LoopbackSockets.readExact(inputStream, payloadLength);
        byte[] packet = new byte[TYPED_HEADER_LENGTH + payloadLength];
        System.arraycopy(header, 0, packet, 0, TYPED_HEADER_LENGTH);
        System.arraycopy(payload, 0, packet, TYPED_HEADER_LENGTH, payloadLength);
        return packet;
    }

    private static byte[] readyForQuery() {
        return CODEC.packet('Z', new byte[]{'I'});
    }

    /** An untyped startup-family message: length, protocol version, then key/values. */
    private static byte[] startupMessage(String... keyValues) {
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        payload.writeBytes(int4(PROTOCOL_VERSION_3));
        for (String keyValue : keyValues) {
            payload.writeBytes(keyValue.getBytes(StandardCharsets.US_ASCII));
            payload.write(0);
        }
        payload.write(0);
        byte[] body = payload.toByteArray();
        java.io.ByteArrayOutputStream message = new java.io.ByteArrayOutputStream();
        message.writeBytes(int4(body.length + PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH));
        message.writeBytes(body);
        return message.toByteArray();
    }

    private static byte[] int4(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)};
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
