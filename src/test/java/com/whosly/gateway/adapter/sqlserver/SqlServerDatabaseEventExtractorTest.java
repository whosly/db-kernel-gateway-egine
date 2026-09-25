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
    void ignoresTargetToClientDirection() {
        SqlServerDatabaseEventExtractor extractor =
                new SqlServerDatabaseEventExtractor("SQLServer", "s", new SqlServerSession("s"));
        byte[] packet = CODEC.packet(TdsPacketType.TABULAR_RESULT.getCode(), new byte[]{1, 2}, 1);
        assertThat(extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, packet, 0, packet.length))
                .isEmpty();
    }
}
