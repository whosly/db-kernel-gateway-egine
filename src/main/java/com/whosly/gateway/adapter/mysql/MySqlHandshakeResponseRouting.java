package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.RoutingContext;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Parses client identity from a MySQL Handshake Response 41 payload into a
 * protocol-agnostic {@link RoutingContext}.
 *
 * <p>MySQL is server-first: the backend (or a forged greeting — forbidden by the
 * transparency rules) must speak before the client sends this packet. The adapter
 * therefore cannot call {@code acquire(context)} with a non-empty context for the
 * initial connect without breaking transparent auth. This parser still centralizes
 * identity extraction so:</p>
 * <ul>
 *   <li>observation ({@link MySQLDatabaseEventExtractor}) and future deferred-connect
 *       designs share one implementation;</li>
 *   <li>Oracle / SQL Server adapters can mirror the same {@link RoutingContext} fill
 *       pattern;</li>
 *   <li>unit tests prove username / database are recoverable from wire bytes.</li>
 * </ul>
 *
 * <p>Auth response bytes are skipped and never returned.</p>
 */
public final class MySqlHandshakeResponseRouting {

    private MySqlHandshakeResponseRouting() {
    }

    /**
     * @param payload Handshake Response packet payload (no 4-byte MySQL header)
     * @return routing context; empty when the payload is too short or not PROTOCOL_41
     */
    public static RoutingContext fromHandshakeResponsePayload(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < 32) {
            return RoutingContext.empty();
        }
        long capabilityFlags = readCapabilityFlags(payload);
        if ((capabilityFlags & MySQLCapability.CLIENT_PROTOCOL_41.getFlag()) == 0) {
            return RoutingContext.empty();
        }

        int payloadEnd = payload.length;
        int cursor = 32;
        CursorResult username = readNullTerminated(payload, cursor, payloadEnd);
        if (username == null) {
            return RoutingContext.empty();
        }
        cursor = skipAuthResponse(payload, username.nextOffset(), payloadEnd, capabilityFlags);
        if (cursor < 0) {
            return RoutingContext.ofUsername(username.value());
        }

        String database = null;
        if ((capabilityFlags & MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()) != 0) {
            CursorResult db = readNullTerminated(payload, cursor, payloadEnd);
            if (db != null && !db.value().isEmpty()) {
                database = db.value();
            }
        }

        if (database == null) {
            return RoutingContext.ofUsername(username.value());
        }
        return RoutingContext.of(username.value(), database);
    }

    /**
     * Convenience for a full MySQL packet (header + payload).
     */
    public static RoutingContext fromPacket(byte[] packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.length <= MySQLFrameCodec.HEADER_LENGTH) {
            return RoutingContext.empty();
        }
        int payloadLength = MySQLFrameCodec.payloadLength(packet, 0, packet.length);
        if (payloadLength < 0 || MySQLFrameCodec.HEADER_LENGTH + payloadLength > packet.length) {
            return RoutingContext.empty();
        }
        byte[] payload = new byte[payloadLength];
        System.arraycopy(packet, MySQLFrameCodec.HEADER_LENGTH, payload, 0, payloadLength);
        return fromHandshakeResponsePayload(payload);
    }

    private static long readCapabilityFlags(byte[] payload) {
        return (payload[0] & 0xFFL)
                | ((payload[1] & 0xFFL) << 8)
                | ((payload[2] & 0xFFL) << 16)
                | ((payload[3] & 0xFFL) << 24);
    }

    private static CursorResult readNullTerminated(byte[] bytes, int offset, int endExclusive) {
        if (offset >= endExclusive) {
            return null;
        }
        int cursor = offset;
        while (cursor < endExclusive && bytes[cursor] != 0) {
            cursor++;
        }
        if (cursor >= endExclusive) {
            return null;
        }
        return new CursorResult(
                new String(bytes, offset, cursor - offset, StandardCharsets.UTF_8),
                cursor + 1);
    }

    private static int skipAuthResponse(byte[] bytes, int offset, int endExclusive, long capabilityFlags) {
        if (offset >= endExclusive) {
            return -1;
        }
        if ((capabilityFlags & MySQLCapability.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA.getFlag()) != 0) {
            int length = bytes[offset] & 0xFF;
            int next = offset + 1 + length;
            return next <= endExclusive ? next : -1;
        }
        if ((capabilityFlags & MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()) != 0) {
            int length = bytes[offset] & 0xFF;
            int next = offset + 1 + length;
            return next <= endExclusive ? next : -1;
        }
        // Null-terminated auth response (rare PROTOCOL_41 without secure connection).
        CursorResult skipped = readNullTerminated(bytes, offset, endExclusive);
        return skipped == null ? -1 : skipped.nextOffset();
    }

    private record CursorResult(String value, int nextOffset) {
    }
}
