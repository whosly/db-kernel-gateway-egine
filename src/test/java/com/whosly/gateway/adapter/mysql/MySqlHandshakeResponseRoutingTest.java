package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.RoutingContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlHandshakeResponseRoutingTest {

    @Test
    void parsesUsernameAndDatabaseFromHandshakeResponse41() {
        byte[] packet = handshakeResponse(
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                        | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()
                        | MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()
                        | MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag(),
                "appuser",
                new byte[20],
                "demo",
                "mysql_native_password");

        assertThat(MySqlHandshakeResponseRouting.fromPacket(packet))
                .isEqualTo(RoutingContext.of("appuser", "demo"));
    }

    @Test
    void parsesUsernameOnlyWhenConnectWithDbAbsent() {
        byte[] packet = handshakeResponse(
                MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                        | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()
                        | MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag(),
                "readonly",
                new byte[20],
                null,
                "mysql_native_password");

        assertThat(MySqlHandshakeResponseRouting.fromPacket(packet))
                .isEqualTo(RoutingContext.ofUsername("readonly"));
    }

    @Test
    void emptyWhenPayloadTooShort() {
        assertThat(MySqlHandshakeResponseRouting.fromHandshakeResponsePayload(new byte[8]).isEmpty()).isTrue();
    }

    private static byte[] handshakeResponse(long capabilities, String username, byte[] authResponse,
                                            String database, String authPlugin) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int index = 0; index < 4; index++) {
            payload.write((int) ((capabilities >> (index * 8)) & 0xFF));
        }
        payload.writeBytes(new byte[]{0, 0, 0, 1});
        payload.write(0x21);
        payload.writeBytes(new byte[23]);
        payload.writeBytes(cstring(username));
        payload.write(authResponse.length & 0xFF);
        payload.writeBytes(authResponse);
        if (database != null) {
            payload.writeBytes(cstring(database));
        }
        if (authPlugin != null) {
            payload.writeBytes(cstring(authPlugin));
        }
        return rawPacket(1, payload.toByteArray());
    }

    private static byte[] rawPacket(int sequenceId, byte[] payload) {
        byte[] packet = new byte[4 + payload.length];
        packet[0] = (byte) (payload.length & 0xFF);
        packet[1] = (byte) ((payload.length >> 8) & 0xFF);
        packet[2] = (byte) ((payload.length >> 16) & 0xFF);
        packet[3] = (byte) (sequenceId & 0xFF);
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }

    private static byte[] cstring(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] cstring = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, cstring, 0, bytes.length);
        return cstring;
    }
}
