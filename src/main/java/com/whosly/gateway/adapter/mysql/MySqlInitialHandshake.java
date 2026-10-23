package com.whosly.gateway.adapter.mysql;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses a MySQL Protocol 10 Initial Handshake payload (no 4-byte packet header).
 *
 * <p>Used by deferred identity routing to extract scramble + auth plugin from a
 * <em>real</em> backend greeting (never forged).</p>
 */
public final class MySqlInitialHandshake {

    public static final int PROTOCOL_VERSION_10 = 10;

    private final int protocolVersion;
    private final String serverVersion;
    private final long connectionId;
    private final byte[] scramble;
    private final int capabilityFlags;
    private final int characterSet;
    private final int statusFlags;
    private final String authPluginName;

    private MySqlInitialHandshake(int protocolVersion, String serverVersion, long connectionId,
                                  byte[] scramble, int capabilityFlags, int characterSet,
                                  int statusFlags, String authPluginName) {
        this.protocolVersion = protocolVersion;
        this.serverVersion = serverVersion;
        this.connectionId = connectionId;
        this.scramble = scramble;
        this.capabilityFlags = capabilityFlags;
        this.characterSet = characterSet;
        this.statusFlags = statusFlags;
        this.authPluginName = authPluginName;
    }

    public static Optional<MySqlInitialHandshake> tryParse(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < 22 || (payload[0] & 0xFF) != PROTOCOL_VERSION_10) {
            return Optional.empty();
        }
        int cursor = 1;
        int versionEnd = indexOfNul(payload, cursor);
        if (versionEnd < 0 || versionEnd + 1 + 4 + 8 + 1 + 2 > payload.length) {
            return Optional.empty();
        }
        String serverVersion = new String(payload, cursor, versionEnd - cursor, StandardCharsets.US_ASCII);
        cursor = versionEnd + 1;
        long connectionId = readUint32Le(payload, cursor);
        cursor += 4;
        byte[] part1 = Arrays.copyOfRange(payload, cursor, cursor + 8);
        cursor += 8;
        cursor += 1; // filler
        int capsLower = readUint16Le(payload, cursor);
        cursor += 2;
        if (cursor + 1 + 2 + 2 + 1 + 10 > payload.length) {
            return Optional.empty();
        }
        int characterSet = payload[cursor] & 0xFF;
        cursor += 1;
        int statusFlags = readUint16Le(payload, cursor);
        cursor += 2;
        int capsUpper = readUint16Le(payload, cursor);
        cursor += 2;
        int capabilityFlags = (capsUpper << 16) | capsLower;
        int authPluginDataLen = payload[cursor] & 0xFF;
        cursor += 1;
        cursor += 10; // reserved
        boolean pluginAuth = (capabilityFlags & (int) MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag()) != 0;
        int part2Len = pluginAuth ? Math.max(13, authPluginDataLen - 8) : 13;
        if (cursor + part2Len > payload.length) {
            return Optional.empty();
        }
        // part2 includes a trailing NUL in the wire scramble region; exclude it from scramble bytes
        int scramblePart2Len = part2Len;
        while (scramblePart2Len > 0 && payload[cursor + scramblePart2Len - 1] == 0) {
            scramblePart2Len--;
        }
        byte[] scramble = new byte[8 + scramblePart2Len];
        System.arraycopy(part1, 0, scramble, 0, 8);
        System.arraycopy(payload, cursor, scramble, 8, scramblePart2Len);
        cursor += part2Len;
        String authPluginName = "mysql_native_password";
        if (pluginAuth && cursor < payload.length) {
            int nameEnd = indexOfNul(payload, cursor);
            if (nameEnd < 0) {
                authPluginName = new String(payload, cursor, payload.length - cursor, StandardCharsets.US_ASCII);
            } else {
                authPluginName = new String(payload, cursor, nameEnd - cursor, StandardCharsets.US_ASCII);
            }
        }
        return Optional.of(new MySqlInitialHandshake(
                PROTOCOL_VERSION_10, serverVersion, connectionId, scramble,
                capabilityFlags, characterSet, statusFlags, authPluginName));
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public long connectionId() {
        return connectionId;
    }

    public byte[] scramble() {
        return Arrays.copyOf(scramble, scramble.length);
    }

    public int capabilityFlags() {
        return capabilityFlags;
    }

    public int characterSet() {
        return characterSet;
    }

    public int statusFlags() {
        return statusFlags;
    }

    public String authPluginName() {
        return authPluginName;
    }

    private static int indexOfNul(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int readUint16Le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long readUint32Le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }
}
