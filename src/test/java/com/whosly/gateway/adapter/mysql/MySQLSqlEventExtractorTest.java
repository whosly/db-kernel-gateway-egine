package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.SqlTrafficEvent;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLSqlEventExtractorTest {

    private final MySQLSqlEventExtractor extractor = new MySQLSqlEventExtractor("MySQL", "mysql-test");

    @Test
    void extractsComQuerySqlFromMySqlPacket() {
        byte[] packet = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");

        List<SqlTrafficEvent> events = extractor.extract(packet, 0, packet.length);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getProtocolName()).isEqualTo("MySQL");
        assertThat(events.get(0).getSessionId()).isEqualTo("mysql-test");
        assertThat(events.get(0).getCommand()).isEqualTo("COM_QUERY");
        assertThat(events.get(0).getSql()).isEqualTo("select 1");
    }

    @Test
    void extractsPreparedStatementSqlFromComStmtPreparePacket() {
        byte[] packet = packet(0, MySQLCommandType.COM_STMT_PREPARE.getCode(),
                "select * from account where id = ?");

        List<SqlTrafficEvent> events = extractor.extract(packet, 0, packet.length);

        assertThat(events).singleElement()
                .satisfies(event -> {
                    assertThat(event.getCommand()).isEqualTo("COM_STMT_PREPARE");
                    assertThat(event.getSql()).isEqualTo("select * from account where id = ?");
                });
    }

    @Test
    void buffersPartialPacketsUntilSqlPacketIsComplete() {
        byte[] packet = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");

        assertThat(extractor.extract(packet, 0, 3)).isEmpty();
        List<SqlTrafficEvent> events = extractor.extract(packet, 3, packet.length - 3);

        assertThat(events).singleElement()
                .extracting(SqlTrafficEvent::getSql)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorSkipsHandshakeResponseBeforeObservingSql() {
        MySQLSqlEventExtractor authenticationAwareExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-auth", false);
        byte[] credentialLikePacket = packet(1, MySQLCommandType.COM_QUERY.getCode(), "credential payload");

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                credentialLikePacket, 0, credentialLikePacket.length)).isEmpty();

        byte[] okPacket = rawPacket(2, new byte[]{0x00});
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, okPacket.length)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        List<SqlTrafficEvent> events = authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length);

        assertThat(events).singleElement()
                .extracting(SqlTrafficEvent::getSql)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorHandlesSplitAuthenticationOkPacket() {
        MySQLSqlEventExtractor authenticationAwareExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-auth", false);
        byte[] okPacket = rawPacket(2, new byte[]{0x00});

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, 2)).isEmpty();
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 2, okPacket.length - 2)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        List<SqlTrafficEvent> events = authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length);

        assertThat(events).singleElement()
                .extracting(SqlTrafficEvent::getSql)
                .isEqualTo("select 1");
    }

    private static byte[] packet(int sequenceId, int command, String sql) {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        int payloadLength = sqlBytes.length + 1;
        byte[] packet = new byte[payloadLength + 4];
        packet[0] = (byte) (payloadLength & 0xFF);
        packet[1] = (byte) ((payloadLength >> 8) & 0xFF);
        packet[2] = (byte) ((payloadLength >> 16) & 0xFF);
        packet[3] = (byte) (sequenceId & 0xFF);
        packet[4] = (byte) command;
        System.arraycopy(sqlBytes, 0, packet, 5, sqlBytes.length);
        return packet;
    }

    private static byte[] rawPacket(int sequenceId, byte[] payload) {
        byte[] packet = new byte[payload.length + 4];
        packet[0] = (byte) (payload.length & 0xFF);
        packet[1] = (byte) ((payload.length >> 8) & 0xFF);
        packet[2] = (byte) ((payload.length >> 16) & 0xFF);
        packet[3] = (byte) (sequenceId & 0xFF);
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }
}
