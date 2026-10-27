package com.whosly.gateway.adapter.mysql;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses / rebuilds MySQL Handshake Response 41 payloads for deferred routing.
 *
 * <p>Auth bytes are carried opaquely; they are never logged. Rebuild replaces only
 * the auth-plugin data (and optionally the plugin name) after an AuthSwitch.</p>
 */
public final class MySqlHandshakeResponseCodec {

    private MySqlHandshakeResponseCodec() {
    }

    public record Parsed(
            long capabilityFlags,
            int maxPacketSize,
            int characterSet,
            String username,
            byte[] authResponse,
            Optional<String> database,
            Optional<String> authPluginName,
            byte[] connectAttrsRemainder) {

        public Parsed {
            Objects.requireNonNull(username, "username");
            authResponse = authResponse == null ? new byte[0] : Arrays.copyOf(authResponse, authResponse.length);
            database = database == null ? Optional.empty() : database;
            authPluginName = authPluginName == null ? Optional.empty() : authPluginName;
            connectAttrsRemainder = connectAttrsRemainder == null
                    ? new byte[0]
                    : Arrays.copyOf(connectAttrsRemainder, connectAttrsRemainder.length);
        }

        public boolean requestsSslOnly() {
            return (capabilityFlags & MySQLCapability.CLIENT_SSL.getFlag()) != 0
                    && username.isEmpty()
                    && authResponse.length == 0
                    && database.isEmpty();
        }
    }

    public static Optional<Parsed> tryParse(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < 32) {
            return Optional.empty();
        }
        long caps = readUint32(payload, 0);
        if ((caps & MySQLCapability.CLIENT_PROTOCOL_41.getFlag()) == 0) {
            return Optional.empty();
        }
        // SSLRequest: fixed 32-byte truncated Handshake Response before TLS.
        if ((caps & MySQLCapability.CLIENT_SSL.getFlag()) != 0 && payload.length <= 36) {
            int maxPacket = (int) readUint32(payload, 4);
            int charset = payload.length > 8 ? (payload[8] & 0xFF) : 0;
            return Optional.of(new Parsed(caps, maxPacket, charset, "", new byte[0],
                    Optional.empty(), Optional.empty(), new byte[0]));
        }
        int maxPacket = (int) readUint32(payload, 4);
        int charset = payload[8] & 0xFF;
        int cursor = 32;
        CursorResult user = readNul(payload, cursor);
        if (user == null) {
            return Optional.empty();
        }
        cursor = user.next;
        CursorBytes auth = readAuth(payload, cursor, caps);
        if (auth == null) {
            return Optional.empty();
        }
        cursor = auth.next;
        Optional<String> database = Optional.empty();
        if ((caps & MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()) != 0) {
            CursorResult db = readNul(payload, cursor);
            if (db == null) {
                return Optional.of(new Parsed(caps, maxPacket, charset, user.value, auth.bytes,
                        Optional.empty(), Optional.empty(), new byte[0]));
            }
            if (!db.value.isEmpty()) {
                database = Optional.of(db.value);
            }
            cursor = db.next;
        }
        Optional<String> plugin = Optional.empty();
        if ((caps & MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag()) != 0) {
            CursorResult p = readNul(payload, cursor);
            if (p != null) {
                if (!p.value.isEmpty()) {
                    plugin = Optional.of(p.value);
                }
                cursor = p.next;
            }
        }
        byte[] attrs = cursor < payload.length
                ? Arrays.copyOfRange(payload, cursor, payload.length)
                : new byte[0];
        return Optional.of(new Parsed(caps, maxPacket, charset, user.value, auth.bytes, database, plugin, attrs));
    }

    /**
     * Rebuilds a Handshake Response 41 payload with replacement auth data / plugin.
     */
    public static byte[] rebuild(Parsed original, byte[] newAuthResponse, String authPluginName) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(newAuthResponse, "newAuthResponse");
        long caps = original.capabilityFlags();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUint32(out, caps);
        writeUint32(out, original.maxPacketSize() & 0xFFFFFFFFL);
        out.write(original.characterSet() & 0xFF);
        out.writeBytes(new byte[23]);
        writeNul(out, original.username());
        writeAuth(out, newAuthResponse, caps);
        if ((caps & MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()) != 0) {
            writeNul(out, original.database().orElse(""));
        }
        if ((caps & MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag()) != 0) {
            String plugin = authPluginName != null && !authPluginName.isBlank()
                    ? authPluginName
                    : original.authPluginName().orElse("mysql_native_password");
            writeNul(out, plugin);
        }
        if ((caps & MySQLCapability.CLIENT_CONNECT_ATTRS.getFlag()) != 0) {
            out.writeBytes(original.connectAttrsRemainder());
        }
        return out.toByteArray();
    }

    private static CursorBytes readAuth(byte[] payload, int offset, long caps) {
        if (offset >= payload.length) {
            return new CursorBytes(new byte[0], offset);
        }
        if ((caps & MySQLCapability.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA.getFlag()) != 0) {
            int length = payload[offset] & 0xFF;
            int next = offset + 1 + length;
            if (next > payload.length) {
                return null;
            }
            return new CursorBytes(Arrays.copyOfRange(payload, offset + 1, next), next);
        }
        if ((caps & MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()) != 0) {
            int length = payload[offset] & 0xFF;
            int next = offset + 1 + length;
            if (next > payload.length) {
                return null;
            }
            return new CursorBytes(Arrays.copyOfRange(payload, offset + 1, next), next);
        }
        CursorResult nul = readNul(payload, offset);
        if (nul == null) {
            return null;
        }
        return new CursorBytes(nul.value.getBytes(StandardCharsets.UTF_8), nul.next);
    }

    private static void writeAuth(ByteArrayOutputStream out, byte[] auth, long caps) {
        if ((caps & MySQLCapability.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA.getFlag()) != 0
                || (caps & MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()) != 0) {
            out.write(auth.length & 0xFF);
            out.writeBytes(auth);
            return;
        }
        out.writeBytes(auth);
        out.write(0);
    }

    private static CursorResult readNul(byte[] bytes, int offset) {
        if (offset > bytes.length) {
            return null;
        }
        int cursor = offset;
        while (cursor < bytes.length && bytes[cursor] != 0) {
            cursor++;
        }
        if (cursor >= bytes.length) {
            return null;
        }
        return new CursorResult(
                new String(bytes, offset, cursor - offset, StandardCharsets.UTF_8),
                cursor + 1);
    }

    private static void writeNul(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static long readUint32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static void writeUint32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }

    private record CursorResult(String value, int next) {
    }

    private record CursorBytes(byte[] bytes, int next) {
    }
}
