package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerDatabaseEventExtractorTest {

    private static final TdsFrameCodec CODEC = new TdsFrameCodec();

    @Test
    void observesLogin7IntoSessionAttributesAndEvent() {
        SqlServerSession session = new SqlServerSession("sqlserver-test-1");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);

        byte[] loginPayload = Login7ObservationTest.buildLogin7Payload("appuser", "appdb", "app", "host1");
        byte[] packet = CODEC.packet(TdsPacketType.TDS7_LOGIN.getCode(), loginPayload, 1);

        List<DatabaseTrafficEvent> events =
                extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, packet, 0, packet.length);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOperation()).isEqualTo("LOGIN7");
        assertThat(events.get(0).getStatement()).contains("appuser").contains("appdb");
        assertThat(session.getLoginUsername()).contains("appuser");
        assertThat(session.getInitialDatabase()).contains("appdb");
        assertThat(session.getAttribute("client.user")).contains("appuser");
        assertThat(session.getAttribute("client.database")).contains("appdb");
    }

    @Test
    void observesSqlBatchTextAsEvent() {
        SqlServerSession session = new SqlServerSession("sqlserver-test-2");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);

        String sql = "SELECT 1";
        byte[] sqlUtf16 = sql.getBytes(StandardCharsets.UTF_16LE);
        // ALL_HEADERS total length = 4 (header with no sub-headers)
        ByteBuffer payload = ByteBuffer.allocate(4 + sqlUtf16.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(4);
        payload.put(sqlUtf16);
        byte[] packet = CODEC.packet(TdsPacketType.SQL_BATCH.getCode(), payload.array(), 1);

        List<DatabaseTrafficEvent> events =
                extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, packet, 0, packet.length);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOperation()).isEqualTo("SQL_BATCH");
        assertThat(events.get(0).getStatement()).isEqualTo("SELECT 1");
    }

    @Test
    void observesErrorTokenFromTargetTabularResult() {
        SqlServerSession session = new SqlServerSession("sqlserver-test-3");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);

        byte[] error = TdsErrorToken.encode(208, 1, 16, "Invalid object name 'nope'", "mssql");
        byte[] done = TdsDoneToken.encodeErrorFinal();
        byte[] payload = new byte[error.length + done.length];
        System.arraycopy(error, 0, payload, 0, error.length);
        System.arraycopy(done, 0, payload, error.length, done.length);
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), payload, 1);

        List<DatabaseTrafficEvent> events =
                extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOperation()).isEqualTo("ERROR");
        assertThat(events.get(0).getStatement()).contains("208").contains("Invalid object name");
        assertThat(events.get(0).getAttribute("number")).contains("208");
        assertThat(events.get(0).getAttribute("class")).contains("16");
        assertThat(events.get(0).getAttribute("server")).contains("mssql");
        assertThat(session.getLastErrorNumber()).contains(208);
        assertThat(session.getLastErrorMessage()).contains("Invalid object name 'nope'");
    }

    @Test
    void ignoresNonTabularTargetPackets() {
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", "s", new SqlServerSession("s"));
        byte[] packet = CODEC.packet(TdsPacketType.PRELOGIN.getCode(), new byte[]{1, 2}, 1);
        assertThat(extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length))
                .isEmpty();
    }

    @Test
    void observesAttentionAndDoneAttnAckOnSameSession() {
        SqlServerSession session = new SqlServerSession("sqlserver-attn-1");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);

        byte[] attention = CODEC.packet(TdsPacketType.ATTENTION.getCode(), new byte[0], 1);
        List<DatabaseTrafficEvent> attnEvents =
                extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, attention, 0, attention.length);

        assertThat(attnEvents).hasSize(1);
        assertThat(attnEvents.get(0).getOperation()).isEqualTo("ATTENTION");
        assertThat(attnEvents.get(0).getAttribute("sameConnection")).contains("true");
        assertThat(session.isAttentionRequested()).isTrue();
        assertThat(session.isAttentionAcknowledged()).isFalse();

        byte[] doneAttn = TdsDoneToken.encodeAttentionAck();
        byte[] tabular = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), doneAttn, 1);
        List<DatabaseTrafficEvent> ackEvents =
                extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, tabular, 0, tabular.length);

        assertThat(ackEvents).hasSize(1);
        assertThat(ackEvents.get(0).getOperation()).isEqualTo("ATTENTION_ACK");
        assertThat(ackEvents.get(0).getStatement()).isEqualTo("DONE_ATTN");
        assertThat(session.isAttentionRequested()).isFalse();
        assertThat(session.isAttentionAcknowledged()).isTrue();
        assertThat(session.getAttribute("last.attention.ack")).contains("true");
    }

    @Test
    void login7EventCarriesRoutingHintWithoutDrivingAcquire() {
        SqlServerSession session = new SqlServerSession("sqlserver-hint-1");
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", session.getConnectionId(), session);

        byte[] loginPayload = Login7ObservationTest.buildLogin7Payload("routeuser", "routedb", "app", "host");
        byte[] packet = CODEC.packet(TdsPacketType.TDS7_LOGIN.getCode(), loginPayload, 1);
        List<DatabaseTrafficEvent> events =
                extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, packet, 0, packet.length);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAttribute("routingHint")).isPresent();
        assertThat(events.get(0).getAttribute("routingHint").get()).contains("routeuser").contains("routedb");
        assertThat(session.getAttribute("routing.hint")).isPresent();
        // Would-be acquire context only — transparent probe stays empty (PreLogin first).
        assertThat(SqlServerLogin7Routing.fromLogin7Payload(loginPayload).username()).contains("routeuser");
    }
}
