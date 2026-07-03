package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.TrafficDirection;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts SQL statements from cleartext PostgreSQL frontend messages.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLDatabaseEventExtractor {

    private static final int TYPED_HEADER_LENGTH = 5;
    private static final int UNTYPED_STARTUP_HEADER_LENGTH = 4;
    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int GSS_ENCRYPTION_REQUEST_CODE = 80877104;

    private final String protocolName;
    private final String sessionId;
    private final boolean skipInitialStartupMessage;
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    private final Map<String, String> statementsByName = new HashMap<>();
    private final Map<String, String> statementsByPortal = new HashMap<>();
    private boolean startupMessageConsumed;
    private boolean awaitingEncryptionResponse;
    private boolean opaqueTunnel;

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId) {
        this(protocolName, sessionId, true);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId, boolean startupMessageConsumed) {
        this.protocolName = protocolName;
        this.sessionId = sessionId;
        this.skipInitialStartupMessage = !startupMessageConsumed;
        this.startupMessageConsumed = startupMessageConsumed;
    }

    public List<DatabaseTrafficEvent> extract(byte[] bytes, int offset, int length) {
        return extractFrontendMessages(bytes, offset, length);
    }

    public List<DatabaseTrafficEvent> inspect(TrafficDirection direction, byte[] bytes, int offset, int length) {
        if (opaqueTunnel) {
            return List.of();
        }

        if (direction == TrafficDirection.TARGET_TO_CLIENT) {
            observeBackendEncryptionResponse(bytes, offset, length);
            return List.of();
        }

        if (awaitingEncryptionResponse) {
            return List.of();
        }

        if (direction != TrafficDirection.CLIENT_TO_TARGET) {
            return List.of();
        }
        return extractFrontendMessages(bytes, offset, length);
    }

    private void observeBackendEncryptionResponse(byte[] bytes, int offset, int length) {
        if (!awaitingEncryptionResponse || length <= 0) {
            return;
        }

        /*
         * PostgreSQL answers SSLRequest/GSSENCRequest with one byte. Accepted
         * encryption changes the following bytes into TLS/GSS payloads that the
         * gateway must treat as opaque. Rejected encryption leaves the session
         * in cleartext, and the client will send a normal StartupMessage next.
         */
        int responseCode = bytes[offset] & 0xFF;
        awaitingEncryptionResponse = false;
        if (responseCode == 'S' || responseCode == 'G') {
            opaqueTunnel = true;
            pendingBytes.reset();
            return;
        }
        if (responseCode == 'N') {
            startupMessageConsumed = false;
        }
    }

    private List<DatabaseTrafficEvent> extractFrontendMessages(byte[] bytes, int offset, int length) {
        pendingBytes.write(bytes, offset, length);
        byte[] buffered = pendingBytes.toByteArray();
        int cursor = 0;
        List<DatabaseTrafficEvent> events = new ArrayList<>();

        if (skipInitialStartupMessage && !startupMessageConsumed) {
            if (buffered.length < UNTYPED_STARTUP_HEADER_LENGTH) {
                return List.of();
            }

            int startupLength = int4(buffered, 0);
            if (startupLength < UNTYPED_STARTUP_HEADER_LENGTH) {
                compact(buffered, 0);
                return List.of();
            }

            if (buffered.length < startupLength) {
                return List.of();
            }

            if (startupLength == 8 && isEncryptionRequest(buffered)) {
                awaitingEncryptionResponse = true;
                compact(buffered, startupLength);
                return List.of();
            }

            cursor = startupLength;
            startupMessageConsumed = true;
        }

        while (buffered.length - cursor >= TYPED_HEADER_LENGTH) {
            char type = (char) (buffered[cursor] & 0xFF);
            int messageLength = int4(buffered, cursor + 1);
            if (messageLength < 4) {
                break;
            }

            int totalLength = 1 + messageLength;
            if (buffered.length - cursor < totalLength) {
                break;
            }

            Optional<DatabaseTrafficEvent> event = extractMessage(type, buffered, cursor + TYPED_HEADER_LENGTH,
                    messageLength - 4);
            event.ifPresent(events::add);
            cursor += totalLength;
        }

        compact(buffered, cursor);
        return events;
    }

    private Optional<DatabaseTrafficEvent> extractMessage(char type, byte[] message, int payloadOffset, int payloadLength) {
        if (type == PostgreSQLMessageType.QUERY.getCode()) {
            String sql = readCString(message, payloadOffset, payloadOffset + payloadLength).value();
            return sql.isBlank()
                    ? Optional.empty()
                    : Optional.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "QUERY", sql).build());
        }

        if (type == PostgreSQLMessageType.PARSE.getCode()) {
            CString statementName = readCString(message, payloadOffset, payloadOffset + payloadLength);
            CString sql = readCString(message, statementName.nextOffset(), payloadOffset + payloadLength);
            statementsByName.put(statementName.value(), sql.value());
            return sql.value().isBlank()
                    ? Optional.empty()
                    : Optional.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "PARSE", sql.value())
                    .attribute("statementName", statementName.value())
                    .build());
        }

        if (type == PostgreSQLMessageType.BIND.getCode()) {
            CString portalName = readCString(message, payloadOffset, payloadOffset + payloadLength);
            CString statementName = readCString(message, portalName.nextOffset(), payloadOffset + payloadLength);
            String sql = statementsByName.get(statementName.value());
            if (sql != null) {
                statementsByPortal.put(portalName.value(), sql);
            }
            return Optional.empty();
        }

        if (type == PostgreSQLMessageType.EXECUTE.getCode()) {
            CString portalName = readCString(message, payloadOffset, payloadOffset + payloadLength);
            String sql = statementsByPortal.get(portalName.value());
            return sql == null || sql.isBlank()
                    ? Optional.empty()
                    : Optional.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "EXECUTE", sql)
                    .attribute("portalName", portalName.value())
                    .build());
        }

        return Optional.empty();
    }

    private static int int4(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static boolean isEncryptionRequest(byte[] bytes) {
        int requestCode = int4(bytes, 4);
        return requestCode == SSL_REQUEST_CODE || requestCode == GSS_ENCRYPTION_REQUEST_CODE;
    }

    private static CString readCString(byte[] bytes, int offset, int endExclusive) {
        int cursor = offset;
        while (cursor < endExclusive && bytes[cursor] != 0) {
            cursor++;
        }
        String value = new String(bytes, offset, cursor - offset, StandardCharsets.UTF_8);
        int nextOffset = cursor < endExclusive ? cursor + 1 : cursor;
        return new CString(value, nextOffset);
    }

    private void compact(byte[] buffered, int consumed) {
        pendingBytes.reset();
        if (consumed < buffered.length) {
            pendingBytes.write(buffered, consumed, buffered.length - consumed);
        }
    }

    private record CString(String value, int nextOffset) {
    }
}
