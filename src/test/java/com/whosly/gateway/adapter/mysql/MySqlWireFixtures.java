package com.whosly.gateway.adapter.mysql;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Shared MySQL handshake wire builders for unit tests. */
final class MySqlWireFixtures {

    private MySqlWireFixtures() {
    }

    static byte[] initialHandshake(String serverVersion, long connectionId, byte[] scramble20,
                                   String authPlugin) {
        if (scramble20.length != 20) {
            throw new IllegalArgumentException("test scramble must be 20 bytes");
        }
        int caps = (int) (MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()
                | MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag()
                | MySQLCapability.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA.getFlag()
                | MySQLCapability.CLIENT_DEPRECATE_EOF.getFlag());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10);
        out.writeBytes(serverVersion.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        writeUint32(out, connectionId);
        out.writeBytes(Arrays.copyOfRange(scramble20, 0, 8));
        out.write(0);
        out.write(caps & 0xFF);
        out.write((caps >> 8) & 0xFF);
        out.write(0x21); // charset
        out.write(0x02); // status AUTOCOMMIT
        out.write(0x00);
        out.write((caps >> 16) & 0xFF);
        out.write((caps >> 24) & 0xFF);
        out.write(21); // auth plugin data len
        out.writeBytes(new byte[10]);
        out.writeBytes(Arrays.copyOfRange(scramble20, 8, 20));
        out.write(0);
        out.writeBytes(authPlugin.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        return out.toByteArray();
    }

    static byte[] handshakeResponsePayload(long capabilities, String username, byte[] authResponse,
                                           String database, String authPlugin) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeUint32(payload, capabilities);
        writeUint32(payload, 0x01000000L);
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
        return payload.toByteArray();
    }

    static byte[] packet(int sequenceId, byte[] payload) {
        byte[] packet = new byte[4 + payload.length];
        packet[0] = (byte) (payload.length & 0xFF);
        packet[1] = (byte) ((payload.length >> 8) & 0xFF);
        packet[2] = (byte) ((payload.length >> 16) & 0xFF);
        packet[3] = (byte) (sequenceId & 0xFF);
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }

    static byte[] okPayload() {
        return new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00};
    }

    private static void writeUint32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }

    private static byte[] cstring(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] cstring = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, cstring, 0, bytes.length);
        return cstring;
    }
}
