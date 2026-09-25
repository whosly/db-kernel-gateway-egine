package com.whosly.gateway.adapter.sqlserver;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Parsed identity fields from a TDS Login7 payload (observation only).
 *
 * <p>Does <em>not</em> extract the password. Offsets follow MS-TDS Login7
 * OffsetLength after the 36-byte fixed header. Best-effort: malformed or
 * truncated payloads yield empty optionals rather than throwing into the
 * relay path.</p>
 */
public final class Login7Observation {

    private final String username;
    private final String database;
    private final String appName;
    private final String hostName;

    private Login7Observation(String username, String database, String appName, String hostName) {
        this.username = username;
        this.database = database;
        this.appName = appName;
        this.hostName = hostName;
    }

    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    public Optional<String> database() {
        return Optional.ofNullable(database);
    }

    public Optional<String> appName() {
        return Optional.ofNullable(appName);
    }

    public Optional<String> hostName() {
        return Optional.ofNullable(hostName);
    }

    /**
     * Parses a Login7 <em>payload</em> (bytes after the 8-byte TDS packet header,
     * or the concatenated payloads of a multi-packet Login7 message).
     *
     * @return empty when the buffer is too short or offsets are unusable
     */
    public static Optional<Login7Observation> tryParse(byte[] payload) {
        if (payload == null || payload.length < 72) {
            return Optional.empty();
        }
        // Fixed header ends at 36; OffsetLength pairs then follow.
        String hostName = readUnicode(payload, 36);
        String username = readUnicode(payload, 40);
        // skip password at 44 — never materialise for observation
        String appName = readUnicode(payload, 48);
        String database = readUnicode(payload, 68);
        if (isBlank(username) && isBlank(database) && isBlank(appName) && isBlank(hostName)) {
            return Optional.empty();
        }
        return Optional.of(new Login7Observation(
                blankToNull(username),
                blankToNull(database),
                blankToNull(appName),
                blankToNull(hostName)));
    }

    private static String readUnicode(byte[] payload, int offsetLengthAt) {
        if (payload.length < offsetLengthAt + 4) {
            return null;
        }
        int ib = u16le(payload, offsetLengthAt);
        int cch = u16le(payload, offsetLengthAt + 2);
        if (cch <= 0 || ib < 0) {
            return null;
        }
        int byteLen = cch * 2;
        if (ib + byteLen > payload.length) {
            return null;
        }
        return new String(payload, ib, byteLen, StandardCharsets.UTF_16LE);
    }

    private static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
