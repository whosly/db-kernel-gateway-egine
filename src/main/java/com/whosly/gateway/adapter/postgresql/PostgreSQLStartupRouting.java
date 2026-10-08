package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProbedHandshake;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import com.whosly.gateway.adapter.protocol.RoutingHandshakeProbe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Fills {@link RoutingContext} from a cleartext PostgreSQL StartupMessage before
 * backend acquire.
 *
 * <p>Client-first protocol: the first frontend packet is an untyped startup-family
 * message. When it is a real StartupMessage, {@code user} / {@code database}
 * parameters are available immediately. {@code SSLRequest} / {@code GSSENCRequest}
 * / {@code CancelRequest} yield an empty context (identity is not present); the
 * consumed bytes are still returned for transparent replay.</p>
 */
public final class PostgreSQLStartupRouting implements RoutingHandshakeProbe {

    private static final PostgreSQLFrameCodec FRAME_CODEC = new PostgreSQLFrameCodec();

    private final int firstMessageTimeoutMillis;

    public PostgreSQLStartupRouting(int firstMessageTimeoutMillis) {
        if (firstMessageTimeoutMillis <= 0) {
            throw new IllegalArgumentException("firstMessageTimeoutMillis must be positive");
        }
        this.firstMessageTimeoutMillis = firstMessageTimeoutMillis;
    }

    @Override
    public ProbedHandshake probe(Socket clientSocket) throws IOException {
        Objects.requireNonNull(clientSocket, "clientSocket");
        int previousTimeout = clientSocket.getSoTimeout();
        try {
            clientSocket.setSoTimeout(firstMessageTimeoutMillis);
            ProtocolMessage first = FRAME_CODEC.readStartupMessage(clientSocket.getInputStream());
            byte[] raw = encodeUntyped(first);
            int requestCode = requestCode(first);
            if (requestCode == PostgreSQLFrameCodec.CANCEL_REQUEST_CODE
                    || requestCode == PostgreSQLFrameCodec.SSL_REQUEST_CODE
                    || requestCode == PostgreSQLFrameCodec.GSSENC_REQUEST_CODE) {
                return ProbedHandshake.of(RoutingContext.empty(), raw);
            }
            return ProbedHandshake.of(fromStartupPayload(first.payload()), raw);
        } catch (SocketTimeoutException e) {
            // Nothing reliably consumed; acquire with empty context (fallback route).
            return ProbedHandshake.empty();
        } finally {
            try {
                clientSocket.setSoTimeout(previousTimeout);
            } catch (IOException ignored) {
                // Closing path may race; ignore restore failures.
            }
        }
    }

    /**
     * Parses {@code user} / {@code database} from a StartupMessage payload
     * (protocol version + key/value cstrings). Auth material is not present in
     * StartupMessage.
     */
    public static RoutingContext fromStartupPayload(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return RoutingContext.empty();
        }
        String user = null;
        String database = null;
        int cursor = 4;
        int end = payload.length;
        while (cursor < end && payload[cursor] != 0) {
            CString name = readCString(payload, cursor, end);
            CString value = readCString(payload, name.nextOffset(), end);
            cursor = value.nextOffset();
            if (name.value().isEmpty()) {
                break;
            }
            if ("user".equals(name.value())) {
                user = value.value();
            } else if ("database".equals(name.value())) {
                database = value.value();
            }
        }
        if (user == null && database == null) {
            return RoutingContext.empty();
        }
        if (user != null && database != null) {
            return RoutingContext.of(user, database);
        }
        if (user != null) {
            return RoutingContext.ofUsername(user);
        }
        return RoutingContext.ofDatabase(database);
    }

    public static int requestCode(ProtocolMessage startupFamilyMessage) {
        byte[] payload = startupFamilyMessage.payload();
        return PostgreSQLFrameCodec.readInt4(payload, 0, payload.length);
    }

    static byte[] encodeUntyped(ProtocolMessage message) {
        byte[] payload = message.payload();
        int length = payload.length + PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH;
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        out.write((length >> 24) & 0xFF);
        out.write((length >> 16) & 0xFF);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static CString readCString(byte[] bytes, int offset, int endExclusive) {
        int cursor = offset;
        while (cursor < endExclusive && bytes[cursor] != 0) {
            cursor++;
        }
        String value = new String(bytes, offset, Math.max(0, cursor - offset), StandardCharsets.UTF_8);
        int nextOffset = cursor < endExclusive ? cursor + 1 : cursor;
        return new CString(value, nextOffset);
    }

    private record CString(String value, int nextOffset) {
    }
}
