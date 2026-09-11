package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SQLServerDatabaseEventExtractorTest {

    private final SQLServerDatabaseEventExtractor extractor =
            new SQLServerDatabaseEventExtractor("SQLServer", "tds-test");

    @Test
    void extractsSqlBatchTextFromUtf16Payload() {
        byte[] packet = packet(TdsPacketType.SQL_BATCH, TdsPacket.STATUS_END_OF_MESSAGE, "select 1");

        List<DatabaseTrafficEvent> events = extractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                packet, 0, packet.length);

        assertThat(events).singleElement()
                .satisfies(event -> {
                    assertThat(event.getProtocolName()).isEqualTo("SQLServer");
                    assertThat(event.getSessionId()).isEqualTo("tds-test");
                    assertThat(event.getOperation()).isEqualTo("SQL_BATCH");
                    assertThat(event.getStatement()).isEqualTo("select 1");
                });
    }

    @Test
    void buffersSqlBatchPacketsUntilEndOfMessage() {
        byte[] first = packet(TdsPacketType.SQL_BATCH, 0x00, "select ");
        byte[] second = packet(TdsPacketType.SQL_BATCH, TdsPacket.STATUS_END_OF_MESSAGE, "1");

        assertThat(extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, first, 0, first.length)).isEmpty();
        List<DatabaseTrafficEvent> events = extractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                second, 0, second.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void skipsPreloginAndLogin7PayloadsWithoutObservingCredentials() {
        byte[] prelogin = packet(TdsPacketType.PRELOGIN, TdsPacket.STATUS_END_OF_MESSAGE,
                preloginEncryptionPayload(EncryptionLevel.ENCRYPT_OFF));
        byte[] login7 = packet(TdsPacketType.LOGIN7, TdsPacket.STATUS_END_OF_MESSAGE,
                "credential-like payload".getBytes(StandardCharsets.UTF_16LE));

        assertThat(extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, prelogin, 0, prelogin.length)).isEmpty();
        assertThat(extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, login7, 0, login7.length)).isEmpty();
    }

    @Test
    void continuesObservationWhenServerNegotiatesCleartextEncryptionOff() {
        byte[] response = packet(TdsPacketType.PRELOGIN, TdsPacket.STATUS_END_OF_MESSAGE,
                preloginEncryptionPayload(EncryptionLevel.ENCRYPT_OFF));

        assertThat(extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, response, 0, response.length)).isEmpty();

        byte[] batch = packet(TdsPacketType.SQL_BATCH, TdsPacket.STATUS_END_OF_MESSAGE, "select 1");
        List<DatabaseTrafficEvent> events = extractor.inspect(TrafficDirection.CLIENT_TO_TARGET,
                batch, 0, batch.length);

        assertThat(events).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select 1");
    }

    @Test
    void stopsObservationWhenServerRequiresEncryptedTraffic() {
        byte[] response = packet(TdsPacketType.PRELOGIN, TdsPacket.STATUS_END_OF_MESSAGE,
                preloginEncryptionPayload(EncryptionLevel.ENCRYPT_REQ));

        assertThat(extractor.inspect(TrafficDirection.TARGET_TO_CLIENT, response, 0, response.length)).isEmpty();

        byte[] batch = packet(TdsPacketType.SQL_BATCH, TdsPacket.STATUS_END_OF_MESSAGE, "select 1");
        assertThat(extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, batch, 0, batch.length)).isEmpty();
    }

    @Test
    void doesNotGuessRpcRequestPayloadAsSqlText() {
        byte[] packet = packet(TdsPacketType.RPC_REQUEST, TdsPacket.STATUS_END_OF_MESSAGE,
                "sp_executesql".getBytes(StandardCharsets.UTF_16LE));

        assertThat(extractor.inspect(TrafficDirection.CLIENT_TO_TARGET, packet, 0, packet.length)).isEmpty();
    }

    private static byte[] packet(TdsPacketType type, int status, String statement) {
        return packet(type, status, statement.getBytes(StandardCharsets.UTF_16LE));
    }

    private static byte[] packet(TdsPacketType type, int status, byte[] payload) {
        byte[] packet = new byte[payload.length + TdsPacket.HEADER_LENGTH];
        int length = packet.length;
        packet[0] = (byte) type.getCode();
        packet[1] = (byte) status;
        packet[2] = (byte) ((length >> 8) & 0xFF);
        packet[3] = (byte) (length & 0xFF);
        packet[6] = 1;
        System.arraycopy(payload, 0, packet, TdsPacket.HEADER_LENGTH, payload.length);
        return packet;
    }

    private static byte[] preloginEncryptionPayload(EncryptionLevel encryptionLevel) {
        return new byte[]{
                0x01, 0x00, 0x06, 0x00, 0x01,
                (byte) 0xff,
                (byte) encryptionLevel.getCode()
        };
    }
}
