package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.TrafficDirection;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts SQL statements from cleartext PostgreSQL frontend messages and
 * observes backend {@code ReadyForQuery} transaction state.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLDatabaseEventExtractor {

    private final String protocolName;
    private final String sessionId;
    private final boolean skipInitialStartupMessage;
    private final PostgreSQLSession session;
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pendingBackendBytes = new ByteArrayOutputStream();
    private final Map<String, String> statementsByName = new HashMap<>();
    private final Map<String, String> statementsByPortal = new HashMap<>();
    private boolean startupMessageConsumed;
    private boolean awaitingEncryptionResponse;
    private boolean opaqueTunnel;
    private boolean cancelRequest;

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId) {
        this(protocolName, sessionId, true, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId, boolean startupMessageConsumed) {
        this(protocolName, sessionId, startupMessageConsumed, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId,
                                            boolean startupMessageConsumed, PostgreSQLSession session) {
        this.protocolName = protocolName;
        this.sessionId = sessionId;
        this.skipInitialStartupMessage = !startupMessageConsumed;
        this.startupMessageConsumed = startupMessageConsumed;
        this.session = session;
    }

    public List<DatabaseTrafficEvent> extract(byte[] bytes, int offset, int length) {
        return extractFrontendMessages(bytes, offset, length);
    }

    public List<DatabaseTrafficEvent> inspect(TrafficDirection direction, byte[] bytes, int offset, int length) {
        if (opaqueTunnel) {
            return List.of();
        }

        if (direction == TrafficDirection.TARGET_TO_CLIENT) {
            observeBackendBytes(bytes, offset, length);
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

    /**
     * @return {@code true} when this session was a PostgreSQL {@code CancelRequest}
     */
    public boolean isCancelRequest() {
        return cancelRequest;
    }

    private void observeBackendEncryptionResponse(byte[] bytes, int offset, int length) {
        if (length <= 0) {
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
        pendingBackendBytes.reset();
        if (responseCode == 'S' || responseCode == 'G') {
            opaqueTunnel = true;
            pendingBytes.reset();
            return;
        }
        if (responseCode == 'N') {
            startupMessageConsumed = false;
        }
    }

    private void observeBackendBytes(byte[] bytes, int offset, int length) {
        if (awaitingEncryptionResponse) {
            observeBackendEncryptionResponse(bytes, offset, length);
            return;
        }
        if (!startupMessageConsumed || cancelRequest) {
            return;
        }

        pendingBackendBytes.write(bytes, offset, length);
        byte[] buffered = pendingBackendBytes.toByteArray();
        int cursor = 0;

        while (buffered.length - cursor >= PostgreSQLFrameCodec.TYPED_HEADER_LENGTH) {
            char type = (char) (buffered[cursor] & 0xFF);
            int messageLength = PostgreSQLFrameCodec.readInt4(buffered, cursor + 1, buffered.length);
            if (messageLength < PostgreSQLFrameCodec.MIN_MESSAGE_LENGTH) {
                break;
            }

            int totalLength = 1 + messageLength;
            if (buffered.length - cursor < totalLength) {
                break;
            }

            observeBackendMessage(type, buffered, cursor + PostgreSQLFrameCodec.TYPED_HEADER_LENGTH,
                    messageLength - PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH);
            cursor += totalLength;
        }

        compactBackend(buffered, cursor);
    }

    private void observeBackendMessage(char type, byte[] message, int payloadOffset, int payloadLength) {
        if (session == null || payloadLength < 1) {
            return;
        }

        Optional<PostgreSQLBackendMessageType> backendType = PostgreSQLBackendMessageType.fromCode(type);
        if (backendType.isEmpty() || backendType.get() != PostgreSQLBackendMessageType.READY_FOR_QUERY) {
            return;
        }

        char status = (char) (message[payloadOffset] & 0xFF);
        session.setTransactionStatus(transactionStatus(status));
        session.tryTransitionTo(ProtocolConnectionState.READY);
    }

    private static PostgreSQLSession.TransactionStatus transactionStatus(char wireCode) {
        return switch (wireCode) {
            case 'T' -> PostgreSQLSession.TransactionStatus.IN_TRANSACTION;
            case 'E' -> PostgreSQLSession.TransactionStatus.FAILED_TRANSACTION;
            default -> PostgreSQLSession.TransactionStatus.IDLE;
        };
    }

    private List<DatabaseTrafficEvent> extractFrontendMessages(byte[] bytes, int offset, int length) {
        pendingBytes.write(bytes, offset, length);
        byte[] buffered = pendingBytes.toByteArray();
        int cursor = 0;
        List<DatabaseTrafficEvent> events = new ArrayList<>();

        if (skipInitialStartupMessage && !startupMessageConsumed) {
            if (buffered.length < PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH) {
                return List.of();
            }

            int startupLength = PostgreSQLFrameCodec.readInt4(buffered, 0, buffered.length);
            if (startupLength < PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH) {
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

            if (startupLength == 16 && isCancelRequest(buffered)) {
                cancelRequest = true;
                compact(buffered, startupLength);
                return List.of();
            }

            observeStartupParameters(buffered, PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH, startupLength);
            cursor = startupLength;
            startupMessageConsumed = true;
            sessionAdvance(ProtocolConnectionState.NEGOTIATING);
            sessionAdvance(ProtocolConnectionState.AUTHENTICATING);
        }

        while (buffered.length - cursor >= PostgreSQLFrameCodec.TYPED_HEADER_LENGTH) {
            char type = (char) (buffered[cursor] & 0xFF);
            int messageLength = PostgreSQLFrameCodec.readInt4(buffered, cursor + 1, buffered.length);
            if (messageLength < PostgreSQLFrameCodec.MIN_MESSAGE_LENGTH) {
                break;
            }

            int totalLength = 1 + messageLength;
            if (buffered.length - cursor < totalLength) {
                break;
            }

            Optional<DatabaseTrafficEvent> event = extractMessage(type, buffered,
                    cursor + PostgreSQLFrameCodec.TYPED_HEADER_LENGTH,
                    messageLength - PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH);
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

    private void observeStartupParameters(byte[] buffered, int payloadOffset, int payloadEnd) {
        if (session == null) {
            return;
        }

        // StartupMessage payload starts with a 4-byte protocol version.
        int cursor = payloadOffset + 4;
        while (cursor < payloadEnd && buffered[cursor] != 0) {
            CString name = readCString(buffered, cursor, payloadEnd);
            CString value = readCString(buffered, name.nextOffset(), payloadEnd);
            cursor = value.nextOffset();
            if (name.value().isEmpty()) {
                break;
            }

            session.setParameter(name.value(), value.value());
            if ("user".equals(name.value())) {
                session.putAttribute("client.user", value.value());
            } else if ("database".equals(name.value())) {
                session.putAttribute("client.database", value.value());
            }
        }
    }

    private void sessionAdvance(ProtocolConnectionState state) {
        if (session != null) {
            session.tryTransitionTo(state);
        }
    }

    private static boolean isEncryptionRequest(byte[] bytes) {
        int requestCode = PostgreSQLFrameCodec.readInt4(bytes,
                PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH, bytes.length);
        return requestCode == PostgreSQLFrameCodec.SSL_REQUEST_CODE
                || requestCode == PostgreSQLFrameCodec.GSSENC_REQUEST_CODE;
    }

    private static boolean isCancelRequest(byte[] bytes) {
        int requestCode = PostgreSQLFrameCodec.readInt4(bytes,
                PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH, bytes.length);
        return requestCode == PostgreSQLFrameCodec.CANCEL_REQUEST_CODE;
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

    private void compactBackend(byte[] buffered, int consumed) {
        pendingBackendBytes.reset();
        if (consumed < buffered.length) {
            pendingBackendBytes.write(buffered, consumed, buffered.length - consumed);
        }
    }

    private record CString(String value, int nextOffset) {
    }
}
