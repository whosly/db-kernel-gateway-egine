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
    void extractsComQuerySqlWhenClientSendsQueryAttributeCounts() {
        MySQLSqlEventExtractor queryAttributeExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-query-attributes", false);
        byte[] handshakeResponse = rawPacket(1,
                capabilityPayload(MySQLCapability.CLIENT_QUERY_ATTRIBUTES.getFlag()
                        | MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        byte[] okPacket = rawPacket(2, new byte[]{0x00});
        queryAttributeExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT, okPacket, 0, okPacket.length);

        byte[] sqlBytes = "select 1".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + 2 + sqlBytes.length];
        payload[0] = (byte) MySQLCommandType.COM_QUERY.getCode();
        payload[1] = 0;
        payload[2] = 1;
        System.arraycopy(sqlBytes, 0, payload, 3, sqlBytes.length);
        byte[] packet = rawPacket(0, payload);

        List<SqlTrafficEvent> events = queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                packet, 0, packet.length);

        assertThat(events).singleElement()
                .extracting(SqlTrafficEvent::getSql)
                .isEqualTo("select 1");
    }

    @Test
    void authenticationAwareExtractorReadsCapabilitiesFromSplitHandshakeResponse() {
        MySQLSqlEventExtractor queryAttributeExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-split-handshake", false);
        byte[] handshakeResponse = rawPacket(1,
                capabilityPayload(MySQLCapability.CLIENT_QUERY_ATTRIBUTES.getFlag()
                        | MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));

        assertThat(queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, 2)).isEmpty();
        assertThat(queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 2, handshakeResponse.length - 2)).isEmpty();
        assertThat(queryAttributeExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                rawPacket(2, new byte[]{0x00}), 0, 5)).isEmpty();

        byte[] sqlBytes = "select 1".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + 2 + sqlBytes.length];
        payload[0] = (byte) MySQLCommandType.COM_QUERY.getCode();
        payload[1] = 0;
        payload[2] = 1;
        System.arraycopy(sqlBytes, 0, payload, 3, sqlBytes.length);

        List<SqlTrafficEvent> events = queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                rawPacket(0, payload), 0, payload.length + 4);

        assertThat(events).singleElement()
                .extracting(SqlTrafficEvent::getSql)
                .isEqualTo("select 1");
    }

    @Test
    void extractsComQuerySqlWhenQueryAttributesContainParameterMetadataAndValues() {
        MySQLSqlEventExtractor queryAttributeExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-query-attributes", false);
        byte[] handshakeResponse = rawPacket(1,
                capabilityPayload(MySQLCapability.CLIENT_QUERY_ATTRIBUTES.getFlag()
                        | MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));
        queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length);
        queryAttributeExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                rawPacket(2, new byte[]{0x00}), 0, 5);

        byte[] sqlBytes = "select @a, @b".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[]{
                (byte) MySQLCommandType.COM_QUERY.getCode(),
                0x02,
                0x01,
                0x00,
                0x01,
                0x08, 0x00, 0x01, 'a',
                (byte) 0xfd, 0x00, 0x01, 'b',
                0x2a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x03, 'f', 'o', 'o'
        };
        byte[] packetPayload = new byte[payload.length + sqlBytes.length];
        System.arraycopy(payload, 0, packetPayload, 0, payload.length);
        System.arraycopy(sqlBytes, 0, packetPayload, payload.length, sqlBytes.length);

        List<SqlTrafficEvent> events = queryAttributeExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                rawPacket(0, packetPayload), 0, packetPayload.length + 4);

        assertThat(events).singleElement()
                .extracting(SqlTrafficEvent::getSql)
                .isEqualTo("select @a, @b");
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
        byte[] credentialLikePacket = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));

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

    @Test
    void authenticationAwareExtractorStopsSqlObservationWhenClientRequestsTls() {
        MySQLSqlEventExtractor authenticationAwareExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-tls", false);
        byte[] sslRequest = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_SSL.getFlag()));

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                sslRequest, 0, sslRequest.length)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length)).isEmpty();
    }

    @Test
    void authenticationAwareExtractorStopsSqlObservationWhenClientRequestsCompression() {
        MySQLSqlEventExtractor authenticationAwareExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-compress", false);
        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_COMPRESS.getFlag()));

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length)).isEmpty();
        byte[] okPacket = rawPacket(2, new byte[]{0x00});
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, okPacket.length)).isEmpty();

        byte[] query = packet(0, MySQLCommandType.COM_QUERY.getCode(), "select 1");
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                query, 0, query.length)).isEmpty();
    }

    @Test
    void authenticationContinuationPayloadDoesNotDisableCleartextObservation() {
        MySQLSqlEventExtractor authenticationAwareExtractor =
                new MySQLSqlEventExtractor("MySQL", "mysql-auth-more", false);
        byte[] handshakeResponse = rawPacket(1, capabilityPayload(MySQLCapability.CLIENT_PROTOCOL_41.getFlag()));

        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                handshakeResponse, 0, handshakeResponse.length)).isEmpty();

        byte[] authContinuationLooksLikeCompression = rawPacket(3,
                capabilityPayload(MySQLCapability.CLIENT_COMPRESS.getFlag()));
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                authContinuationLooksLikeCompression, 0, authContinuationLooksLikeCompression.length)).isEmpty();

        byte[] okPacket = rawPacket(4, new byte[]{0x00});
        assertThat(authenticationAwareExtractor.inspect(TrafficDirection.TARGET_TO_CLIENT,
                okPacket, 0, okPacket.length)).isEmpty();

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

    private static byte[] capabilityPayload(long capabilityFlags) {
        byte[] payload = new byte[32];
        payload[0] = (byte) (capabilityFlags & 0xFF);
        payload[1] = (byte) ((capabilityFlags >> 8) & 0xFF);
        payload[2] = (byte) ((capabilityFlags >> 16) & 0xFF);
        payload[3] = (byte) ((capabilityFlags >> 24) & 0xFF);
        return payload;
    }
}
