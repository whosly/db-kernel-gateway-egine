package com.whosly.gateway.adapter.mysql;

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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the masking path end to end: client and database sockets on both ends of
 * a real relay, with the bytes a client would actually receive.
 *
 * <p>The unit tests show that the interceptor decides correctly. These show that the
 * decision survives everything between it and the wire: the hold buffer, the
 * per-message delivery of a window, the sequence ids and the native error the peer
 * gets when a result set is refused.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
class MySQLResultSetMaskingRelayTest {

    private static final int HEADER_LENGTH = MySQLFrameCodec.HEADER_LENGTH;
    /** MySQL type codes: {@code LONG} is a four-byte integer, two bytes narrower than {@code LONGLONG}. */
    private static final int INT4_TYPE = 0x03;
    private static final int VARCHAR_TYPE = 0x0F;

    @Test
    void masksARowAcrossTheRelayAndLeavesTheOtherColumnIntact() throws Exception {
        try (LoopbackSockets.Pair client = LoopbackSockets.open();
             LoopbackSockets.Pair database = LoopbackSockets.open()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> relayFuture = startRelay(executor, client, database);

                byte[] query = MySQLTestFrames.commandPacket(MySQLCommandType.COM_QUERY);
                client.clientSide().getOutputStream().write(query);
                client.clientSide().getOutputStream().flush();
                // The command path is untouched, byte for byte.
                assertThat(MySQLTestFrames.readExact(database.clientSide().getInputStream(), query.length))
                        .isEqualTo(query);

                OutputStream toRelay = database.clientSide().getOutputStream();
                toRelay.write(MySQLTestFrames.packet(new byte[]{0x02}, 1));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.columnPayload("id", INT4_TYPE, MySQLTestFrames.UTF8_COLLATION), 2));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.columnPayload("email", VARCHAR_TYPE, MySQLTestFrames.UTF8_COLLATION), 3));
                toRelay.write(MySQLTestFrames.columnDefinitionsTerminator(4));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.rowPayload("7", "alice@example.com"), 5));
                toRelay.flush();

                InputStream fromRelay = client.clientSide().getInputStream();
                // Metadata reaches the client exactly as the database sent it.
                for (int sequenceId = 1; sequenceId <= 4; sequenceId++) {
                    assertThat(MySQLTestFrames.readPacket(fromRelay)[3] & 0xFF).isEqualTo(sequenceId);
                }

                byte[] masked = MySQLTestFrames.readPacket(fromRelay);
                assertThat(masked[3] & 0xFF).isEqualTo(5);
                List<byte[]> values = MySQLTextRow.parse(masked, HEADER_LENGTH, masked.length - HEADER_LENGTH)
                        .orElseThrow();
                assertThat(new String(values.get(0), StandardCharsets.US_ASCII)).isEqualTo("7");
                assertThat(values.get(1)).isNull();

                endSession(client, database, relayFuture);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void masksEveryRowWhenSeveralArriveInOneRead() throws Exception {
        try (LoopbackSockets.Pair client = LoopbackSockets.open();
             LoopbackSockets.Pair database = LoopbackSockets.open()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> relayFuture = startRelay(executor, client, database);
                client.clientSide().getOutputStream()
                        .write(MySQLTestFrames.commandPacket(MySQLCommandType.COM_QUERY));
                client.clientSide().getOutputStream().flush();
                MySQLTestFrames.readExact(database.clientSide().getInputStream(), 5);

                OutputStream toRelay = database.clientSide().getOutputStream();
                toRelay.write(MySQLTestFrames.packet(new byte[]{0x01}, 1));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.columnPayload("email", VARCHAR_TYPE, MySQLTestFrames.UTF8_COLLATION), 2));
                toRelay.write(MySQLTestFrames.columnDefinitionsTerminator(3));
                /*
                 * Two rows in a single write. Each is a message of its own, and a rewrite
                 * must see them one at a time: handing both over as one message would make
                 * the gateway parse the second row's header as the first row's value.
                 */
                toRelay.write(MySQLTestFrames.packet(MySQLTestFrames.rowPayload("first@example.com"), 4));
                toRelay.write(MySQLTestFrames.packet(MySQLTestFrames.rowPayload("second@example.com"), 5));
                toRelay.flush();

                InputStream fromRelay = client.clientSide().getInputStream();
                for (int sequenceId = 1; sequenceId <= 3; sequenceId++) {
                    MySQLTestFrames.readPacket(fromRelay);
                }

                byte[] firstRow = MySQLTestFrames.readPacket(fromRelay);
                byte[] secondRow = MySQLTestFrames.readPacket(fromRelay);
                assertThat(firstRow[3] & 0xFF).isEqualTo(4);
                assertThat(secondRow[3] & 0xFF).isEqualTo(5);
                assertThat(isNullRow(firstRow)).isTrue();
                assertThat(isNullRow(secondRow)).isTrue();

                endSession(client, database, relayFuture);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void refusesAResultSetItCannotMaskInsteadOfForwardingIt() throws Exception {
        try (LoopbackSockets.Pair client = LoopbackSockets.open();
             LoopbackSockets.Pair database = LoopbackSockets.open()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> relayFuture = startRelay(executor, client, database);
                client.clientSide().getOutputStream()
                        .write(MySQLTestFrames.commandPacket(MySQLCommandType.COM_QUERY));
                client.clientSide().getOutputStream().flush();
                MySQLTestFrames.readExact(database.clientSide().getInputStream(), 5);

                OutputStream toRelay = database.clientSide().getOutputStream();
                toRelay.write(MySQLTestFrames.packet(new byte[]{0x02}, 1));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.columnPayload("id", INT4_TYPE, MySQLTestFrames.UTF8_COLLATION), 2));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.columnPayload("email", VARCHAR_TYPE, MySQLTestFrames.UTF8_COLLATION), 3));
                toRelay.write(MySQLTestFrames.columnDefinitionsTerminator(4));
                // Three values for two columns: the gateway cannot know which column the
                // rule claims, so the value must not reach the client unmasked.
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.rowPayload("7", "alice@example.com", "extra"), 5));
                toRelay.flush();

                InputStream fromRelay = client.clientSide().getInputStream();
                for (int sequenceId = 1; sequenceId <= 4; sequenceId++) {
                    MySQLTestFrames.readPacket(fromRelay);
                }

                byte[] error = MySQLTestFrames.readPacket(fromRelay);
                assertThat(error[4] & 0xFF).isEqualTo(0xFF);
                assertThat(new String(error, 5, error.length - 5, StandardCharsets.US_ASCII))
                        .doesNotContain("alice@example.com");

                client.clientSide().close();
                relayFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void masksARowOfAPreparedStatementAcrossTheRelay() throws Exception {
        try (LoopbackSockets.Pair client = LoopbackSockets.open();
             LoopbackSockets.Pair database = LoopbackSockets.open()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> relayFuture = startRelay(executor, client, database);
                byte[] execute = MySQLTestFrames.commandPacket(MySQLCommandType.COM_STMT_EXECUTE);
                client.clientSide().getOutputStream().write(execute);
                client.clientSide().getOutputStream().flush();
                MySQLTestFrames.readExact(database.clientSide().getInputStream(), execute.length);

                OutputStream toRelay = database.clientSide().getOutputStream();
                toRelay.write(MySQLTestFrames.packet(new byte[]{0x02}, 1));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.columnPayload("id", INT4_TYPE, MySQLTestFrames.UTF8_COLLATION), 2));
                toRelay.write(MySQLTestFrames.packet(
                        MySQLTestFrames.columnPayload("email", VARCHAR_TYPE, MySQLTestFrames.UTF8_COLLATION), 3));
                toRelay.write(MySQLTestFrames.columnDefinitionsTerminator(4));
                /*
                 * A prepared statement's row: 0x00, the null bitmap (one byte for two
                 * columns), then each value in its own binary layout — a little-endian int
                 * and a length-encoded string.
                 */
                byte[] row = binaryRowPacket(5);
                toRelay.write(row);
                toRelay.flush();

                InputStream fromRelay = client.clientSide().getInputStream();
                for (int sequenceId = 1; sequenceId <= 4; sequenceId++) {
                    MySQLTestFrames.readPacket(fromRelay);
                }

                byte[] masked = MySQLTestFrames.readPacket(fromRelay);
                assertThat(masked[3] & 0xFF).isEqualTo(5);
                assertThat(masked[4] & 0xFF).isZero();
                // Column 1's bit is bit 3, because the bitmap starts two bits in.
                assertThat(masked[5] & 0xFF).isEqualTo(0x08);
                // The int column keeps its bytes, and the masked string is gone entirely:
                // in a binary row NULL is a bit, not a value, so the row really shrank.
                assertThat(Arrays.copyOfRange(masked, 6, 10)).isEqualTo(littleEndian(7, 4));
                assertThat(masked.length).isEqualTo(row.length - LENGTH_ENCODED_EMAIL_LENGTH);
                assertThat(MySQLFrameCodec.payloadLength(masked, 0, masked.length))
                        .isEqualTo(masked.length - HEADER_LENGTH);

                endSession(client, database, relayFuture);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** {@code "alice@example.com"} plus its one-byte length prefix. */
    private static final int LENGTH_ENCODED_EMAIL_LENGTH = 1 + "alice@example.com".length();

    private static byte[] binaryRowPacket(int sequenceId) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x00);                                    // a value row
        payload.write(0x00);                                    // null bitmap: no NULLs yet
        payload.writeBytes(littleEndian(7, 4));
        byte[] email = "alice@example.com".getBytes(StandardCharsets.US_ASCII);
        payload.write(email.length);
        payload.writeBytes(email);
        return MySQLTestFrames.packet(payload.toByteArray(), sequenceId);
    }

    private static byte[] littleEndian(long value, int width) {
        byte[] bytes = new byte[width];
        for (int index = 0; index < width; index++) {
            bytes[index] = (byte) ((value >> (8 * index)) & 0xFF);
        }
        return bytes;
    }

    private static Future<?> startRelay(ExecutorService executor, LoopbackSockets.Pair client,
                                        LoopbackSockets.Pair database) {
        String sessionId = "mysql-mask-relay";
        MySQLSession session = new MySQLSession(sessionId);
        MySQLDatabaseEventExtractor extractor =
                new MySQLDatabaseEventExtractor("MySQL", sessionId, true, session);
        MaskingEngine engine = new MaskingEngine(new MaskingRuleRegistry(
                List.of(new NullingRule("null-email", 10, ColumnSelector.named("email")))));
        DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                extractor::inspect,
                DatabaseTrafficObserver.noop(),
                DatabaseRiskPolicy.allowAll(),
                session,
                new StatementClassifier(new DruidSqlParser()));
        MessagePipeline pipeline = MessagePipeline.of(inspector,
                new MySQLResultSetMaskingInterceptor(extractor, engine));
        DuplexRelay relay = new DuplexRelay(sessionId, pipeline,
                new ProtocolErrorResponder(new MySQLFrameCodec(), new MySqlGatewayErrorMapper()),
                RewriteLimits.defaults());

        return executor.submit(() -> {
            try {
                relay.relay(client.serverSide(), database.serverSide());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void endSession(LoopbackSockets.Pair client, LoopbackSockets.Pair database,
                                   Future<?> relayFuture) throws Exception {
        client.clientSide().close();
        database.clientSide().close();
        relayFuture.get(2, TimeUnit.SECONDS);
    }

    /** True when the row's only value is SQL NULL. */
    private static boolean isNullRow(byte[] row) {
        return MySQLTextRow.parse(row, HEADER_LENGTH, row.length - HEADER_LENGTH)
                .map(values -> values.size() == 1 && values.get(0) == null)
                .orElse(false);
    }
}
