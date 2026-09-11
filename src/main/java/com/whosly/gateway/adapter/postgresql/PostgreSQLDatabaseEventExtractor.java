package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import com.whosly.gateway.adapter.protocol.TrafficDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observes cleartext PostgreSQL traffic and extracts auditable SQL events.
 *
 * <p>The relay forwards the original bytes; this extractor only reads them. It
 * tracks the two directions independently:</p>
 * <ul>
 *   <li>client to target: the startup family (StartupMessage / SSLRequest /
 *       GSSENCRequest / CancelRequest), then simple and extended query messages.
 *       Extended query SQL is correlated from Parse through Bind to Execute;</li>
 *   <li>target to client: the authentication exchange plus the response
 *       messages that carry transaction state and command metadata.</li>
 * </ul>
 *
 * <p>Accepted TLS/GSS encryption turns the session into an opaque tunnel where
 * no further inspection happens. Anything that cannot be parsed confidently is
 * dropped rather than guessed.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLDatabaseEventExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLDatabaseEventExtractor.class);

    private final String protocolName;
    private final String sessionId;
    /** True when construction starts before the startup message has been seen. */
    private final boolean skipInitialStartupMessage;
    /** Optional session used to publish observed protocol state; null in some tests. */
    private final PostgreSQLSession session;
    /** Buffer for frontend messages, which TCP may split across reads. */
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    /** Buffer for backend messages used for transaction/metadata observation. */
    private final ByteArrayOutputStream pendingBackendBytes = new ByteArrayOutputStream();
    /** Parse-time mapping of statement name to SQL text. */
    private final Map<String, String> statementsByName = new HashMap<>();
    /** Bind-time mapping of portal name to SQL text. */
    private final Map<String, String> statementsByPortal = new HashMap<>();
    /** True once the StartupMessage has been consumed. */
    private boolean startupMessageConsumed;
    /** True while waiting for the single-byte SSL/GSS encoding response. */
    private boolean awaitingEncryptionResponse;
    /** True after accepted encryption: bytes become opaque and are not parsed. */
    private boolean opaqueTunnel;
    /** True when this session was a CancelRequest. */
    private boolean cancelRequest;
    /** Optional index correlating CancelRequest keys with sessions. */
    private final PostgreSQLCancelKeyRegistry cancelKeyRegistry;
    /** Process id carried by an observed CancelRequest; -1 when none was seen. */
    private int cancelRequestProcessId = -1;
    /** Secret key carried by an observed CancelRequest; memory only, never logged. */
    private int cancelRequestSecretKey;
    /** Session id the observed CancelRequest targets, when the key is known. */
    private String cancelTargetSessionId;
    /** Count of malformed-frame anomalies; forwarding is never affected by them. */
    private final AtomicLong protocolAnomalies = new AtomicLong();
    private final AtomicBoolean protocolAnomalyLogged = new AtomicBoolean();

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId) {
        this(protocolName, sessionId, true, null, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId, boolean startupMessageConsumed) {
        this(protocolName, sessionId, startupMessageConsumed, null, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId,
                                            boolean startupMessageConsumed, PostgreSQLSession session) {
        this(protocolName, sessionId, startupMessageConsumed, session, null);
    }

    public PostgreSQLDatabaseEventExtractor(String protocolName, String sessionId,
                                            boolean startupMessageConsumed, PostgreSQLSession session,
                                            PostgreSQLCancelKeyRegistry cancelKeyRegistry) {
        this.protocolName = protocolName;
        this.sessionId = sessionId;
        this.skipInitialStartupMessage = !startupMessageConsumed;
        this.startupMessageConsumed = startupMessageConsumed;
        this.session = session;
        this.cancelKeyRegistry = cancelKeyRegistry;
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
        /*
         * Backend bytes only become meaningful once the startup message has been
         * consumed; before that the only interesting byte is the single-byte
         * encoding response handled above.
         */
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
                recordProtocolAnomaly("backend message length " + messageLength + " (minimum 4)");
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
        if (session == null) {
            return;
        }

        Optional<PostgreSQLBackendMessageType> backendType = PostgreSQLBackendMessageType.fromCode(type);
        if (backendType.isEmpty()) {
            return;
        }

        switch (backendType.get()) {
            case READY_FOR_QUERY -> observeReadyForQuery(message, payloadOffset, payloadLength);
            case COMMAND_COMPLETE -> session.setLastCommandTag(
                    readCString(message, payloadOffset, payloadOffset + payloadLength).value());
            case ROW_DESCRIPTION -> observeRowDescription(message, payloadOffset, payloadLength);
            case DATA_ROW -> session.incrementResultRows();
            case PARAMETER_DESCRIPTION -> observeParameterDescription(message, payloadOffset, payloadLength);
            // Extended-query acknowledgements (rule 4.7).
            case PARSE_COMPLETE -> session.recordParseComplete();
            case BIND_COMPLETE -> session.recordBindComplete();
            case CLOSE_COMPLETE -> session.recordCloseComplete();
            case NO_DATA -> session.recordNoData();
            case PORTAL_SUSPENDED -> session.recordPortalSuspended();
            case NOTIFICATION_RESPONSE -> observeNotification(message, payloadOffset, payloadLength);
            // COPY moves the session into streaming until the next ReadyForQuery.
            case COPY_IN_RESPONSE, COPY_OUT_RESPONSE, COPY_BOTH_RESPONSE -> {
                session.beginCopy();
                session.tryTransitionTo(ProtocolConnectionState.STREAMING);
            }
            case COPY_DATA -> session.incrementCopyData();
            case PARAMETER_STATUS -> observeParameterStatus(message, payloadOffset, payloadOffset + payloadLength);
            case BACKEND_KEY_DATA -> observeBackendKeyData(message, payloadOffset, payloadLength);
            case AUTHENTICATION -> observeAuthentication(message, payloadOffset, payloadLength);
            case ERROR_RESPONSE -> {
                PgErrorFields fields = readErrorFields(message, payloadOffset, payloadOffset + payloadLength);
                session.setLastSeverity(fields.severity());
                session.setLastSqlState(fields.sqlState());
            }
            case NOTICE_RESPONSE -> {
                PgErrorFields fields = readErrorFields(message, payloadOffset, payloadOffset + payloadLength);
                session.setLastNoticeSeverity(fields.severity());
                session.setLastNoticeSqlState(fields.sqlState());
            }
            default -> {
                // Other backend messages are forwarded verbatim; no metadata is tracked yet.
            }
        }
    }

    private void observeReadyForQuery(byte[] message, int payloadOffset, int payloadLength) {
        if (payloadLength < 1) {
            return;
        }

        char status = (char) (message[payloadOffset] & 0xFF);
        session.setTransactionStatus(transactionStatus(status));
        // ReadyForQuery is also the answer to a pending Sync (rule 4.7).
        session.markSyncCompleted();
        session.tryTransitionTo(ProtocolConnectionState.READY);
    }

    /**
     * Records the channel of a LISTEN/NOTIFY notification.
     *
     * <p>Only the sending process id and channel name are kept: the notification
     * payload is application data and is deliberately not retained.</p>
     */
    private void observeNotification(byte[] message, int payloadOffset, int payloadLength) {
        if (payloadLength < 4) {
            return;
        }

        int processId = PostgreSQLFrameCodec.readInt4(message, payloadOffset, payloadOffset + 4);
        CString channel = readCString(message, payloadOffset + 4, payloadOffset + payloadLength);
        session.recordNotification(processId, channel.value());
    }

    private void observeRowDescription(byte[] message, int payloadOffset, int payloadLength) {
        if (payloadLength < 2) {
            return;
        }

        // A RowDescription also marks the start of a new result set.
        session.beginResultSet();
        int fieldCount = ((message[payloadOffset] & 0xFF) << 8) | (message[payloadOffset + 1] & 0xFF);
        session.setLastRowDescriptionFieldCount(fieldCount);
    }

    private void observeParameterDescription(byte[] message, int payloadOffset, int payloadLength) {
        if (payloadLength < 2) {
            return;
        }

        int parameterCount = ((message[payloadOffset] & 0xFF) << 8) | (message[payloadOffset + 1] & 0xFF);
        session.setLastParameterDescriptionCount(parameterCount);
    }

    /** Session parameters the server reports after authentication. */
    private void observeParameterStatus(byte[] message, int offset, int endExclusive) {
        CString name = readCString(message, offset, endExclusive);
        CString value = readCString(message, name.nextOffset(), endExclusive);
        if (!name.value().isEmpty()) {
            session.setParameter(name.value(), value.value());
        }
    }

    /** Cancel key material used to associate a future cancel request. */
    private void observeBackendKeyData(byte[] message, int payloadOffset, int payloadLength) {
        if (payloadLength < 8) {
            return;
        }
        int processId = PostgreSQLFrameCodec.readInt4(message, payloadOffset, payloadOffset + 4);
        int secretKey = PostgreSQLFrameCodec.readInt4(message, payloadOffset + 4, payloadOffset + 8);
        session.setBackendProcessId(processId);
        session.setBackendSecretKey(secretKey);
        if (cancelKeyRegistry != null) {
            // Enables correlating a later CancelRequest with this session (rule 4.10).
            cancelKeyRegistry.register(sessionId, processId, secretKey);
        }
    }

    /**
     * Correlates an observed {@code CancelRequest} with the session whose
     * {@code BackendKeyData} matches. The request itself keeps being forwarded
     * untouched: the gateway only notes which session it targets.
     */
    private void observeCancelRequest(byte[] buffered) {
        cancelRequestProcessId = PostgreSQLFrameCodec.readInt4(buffered,
                PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH + 4, buffered.length);
        cancelRequestSecretKey = PostgreSQLFrameCodec.readInt4(buffered,
                PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH + 8, buffered.length);
        if (cancelKeyRegistry != null) {
            cancelTargetSessionId = cancelKeyRegistry
                    .findTargetSessionId(cancelRequestProcessId, cancelRequestSecretKey)
                    .orElse(null);
        }
    }

    /**
     * Session id targeted by an observed {@code CancelRequest}.
     *
     * @return the target session, or empty when no active session matches the key
     */
    public Optional<String> getCancelTargetSessionId() {
        return Optional.ofNullable(cancelTargetSessionId);
    }

    /** Backend process id carried by an observed {@code CancelRequest}; -1 when none. */
    public int getCancelRequestProcessId() {
        return cancelRequestProcessId;
    }

    /** Authentication type only; the payload itself is never retained. */
    private void observeAuthentication(byte[] message, int payloadOffset, int payloadLength) {
        if (payloadLength < 4) {
            return;
        }
        session.setLastAuthenticationType(PostgreSQLFrameCodec.readInt4(message, payloadOffset, payloadOffset + 4));
    }

    /**
     * Parses the field list shared by ErrorResponse and NoticeResponse.
     *
     * <p>Only severity and SQLSTATE are kept; message, detail and hint text are
     * intentionally ignored so no backend payload is retained.</p>
     */
    private static PgErrorFields readErrorFields(byte[] message, int offset, int endExclusive) {
        String severity = null;
        String sqlState = null;
        int cursor = offset;
        while (cursor < endExclusive && message[cursor] != 0) {
            char fieldCode = (char) (message[cursor] & 0xFF);
            CString value = readCString(message, cursor + 1, endExclusive);
            if (fieldCode == 'S') {
                severity = value.value();
            } else if (fieldCode == 'C') {
                sqlState = value.value();
            }
            cursor = value.nextOffset();
        }
        return new PgErrorFields(severity, sqlState);
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

        /*
         * The first client message is untyped and must be classified before any
         * typed message parsing: StartupMessage, SSLRequest, GSSENCRequest or
         * CancelRequest. The latter three do not enter the query path.
         */
        if (skipInitialStartupMessage && !startupMessageConsumed) {
            if (buffered.length < PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH) {
                return List.of();
            }

            int startupLength = PostgreSQLFrameCodec.readInt4(buffered, 0, buffered.length);
            if (startupLength < PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH) {
                recordProtocolAnomaly("startup length " + startupLength + " (minimum 4)");
                compact(buffered, 0);
                return List.of();
            }

            if (buffered.length < startupLength) {
                return List.of();
            }

            // SSLRequest / GSSENCRequest: the server answers with a single byte.
            if (startupLength == 8 && isEncryptionRequest(buffered)) {
                awaitingEncryptionResponse = true;
                compact(buffered, startupLength);
                return List.of();
            }

            // CancelRequest is a separate, short-lived connection.
            if (startupLength == 16 && isCancelRequest(buffered)) {
                cancelRequest = true;
                observeCancelRequest(buffered);
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
                recordProtocolAnomaly("message length " + messageLength + " (minimum 4)");
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

    /**
     * Extracts an audit event from one frontend message.
     *
     * <p>Simple query emits an event directly. Extended query has no SQL in its
     * Execute message, so Parse stores statement name to SQL, Bind maps a portal
     * to that statement, and Execute resolves the portal back to its SQL. Close
     * and Describe keep the mirror accurate but emit no event, while Flush and
     * Sync need no action because the following ReadyForQuery is observed
     * separately.</p>
     */
    private Optional<DatabaseTrafficEvent> extractMessage(char type, byte[] message, int payloadOffset, int payloadLength) {
        // Terminate ends the session; the server closes the connection.
        if (type == PostgreSQLMessageType.TERMINATE.getCode()) {
            sessionAdvance(ProtocolConnectionState.CLOSING);
            return Optional.empty();
        }

        /*
         * Every frontend message that opens or advances a query cycle moves the
         * session to EXECUTING; the ReadyForQuery that ends the cycle returns it
         * to READY (rule 2.1/4.7). Observation never gates forwarding, so an
         * illegal transition is ignored rather than raised.
         */
        sessionAdvance(ProtocolConnectionState.EXECUTING);

        /*
         * Sync is the extended-query error-recovery point: after an error the
         * server discards messages until this marker and then answers
         * ReadyForQuery. Recording it keeps the recovery window observable.
         */
        if (type == PostgreSQLMessageType.SYNC.getCode()) {
            if (session != null) {
                session.markSyncRequested();
            }
            return Optional.empty();
        }

        // Simple query: the SQL text is the whole payload.
        if (type == PostgreSQLMessageType.QUERY.getCode()) {
            String sql = readCString(message, payloadOffset, payloadOffset + payloadLength).value();
            return sql.isBlank()
                    ? Optional.empty()
                    : Optional.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "QUERY", sql).build());
        }

        // Extended query step 1: remember statement name -> SQL text.
        if (type == PostgreSQLMessageType.PARSE.getCode()) {
            CString statementName = readCString(message, payloadOffset, payloadOffset + payloadLength);
            CString sql = readCString(message, statementName.nextOffset(), payloadOffset + payloadLength);
            boolean replaced = statementsByName.put(statementName.value(), sql.value()) != null;
            if (session != null && !statementName.value().isEmpty() && !replaced) {
                // Only named statements persist: the unnamed statement is replaced
                // by the next Parse and never accumulates.
                session.markPreparedStatementOpened();
            }
            return sql.value().isBlank()
                    ? Optional.empty()
                    : Optional.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "PARSE", sql.value())
                    .attribute("statementName", statementName.value())
                    .build());
        }

        // Extended query step 2: link a portal to its statement (no event yet).
        if (type == PostgreSQLMessageType.BIND.getCode()) {
            CString portalName = readCString(message, payloadOffset, payloadOffset + payloadLength);
            CString statementName = readCString(message, portalName.nextOffset(), payloadOffset + payloadLength);
            String sql = statementsByName.get(statementName.value());
            if (sql != null) {
                statementsByPortal.put(portalName.value(), sql);
            }
            return Optional.empty();
        }

        // Extended query step 3: resolve the portal back to its SQL and report it.
        if (type == PostgreSQLMessageType.EXECUTE.getCode()) {
            CString portalName = readCString(message, payloadOffset, payloadOffset + payloadLength);
            String sql = statementsByPortal.get(portalName.value());
            return sql == null || sql.isBlank()
                    ? Optional.empty()
                    : Optional.of(DatabaseTrafficEvent.builder(protocolName, sessionId, "EXECUTE", sql)
                    .attribute("portalName", portalName.value())
                    .build());
        }

        // Extended query: Close drops a prepared statement or portal from the mirror.
        if (type == PostgreSQLMessageType.CLOSE.getCode()) {
            CString target = readCString(message, payloadOffset, payloadOffset + payloadLength);
            CString name = readCString(message, target.nextOffset(), payloadOffset + payloadLength);
            forgetPreparedObject(target.value(), name.value());
            if (session != null && "S".equals(target.value())) {
                session.markPreparedStatementClosed();
            }
            return Optional.empty();
        }

        // Extended query: Describe only requests metadata, so there is nothing to audit.
        if (type == PostgreSQLMessageType.DESCRIBE.getCode()) {
            return Optional.empty();
        }

        /*
         * A frontend message the gateway does not model is still self-delimiting,
         * so parsing of the following messages stays safe; only its meaning is
         * unknown. That degrades observation to UNCERTAIN instead of suspending it
         * (rule 2.10), because a PostgreSQL frame needs no boundary recovery.
         */
        if (PostgreSQLMessageType.fromCode(type).isEmpty() && session != null) {
            session.markObservationUncertain();
        }
        return Optional.empty();
    }

    /**
     * Forgets a prepared statement or portal that the client closed. The target
     * is {@code S} for a statement and {@code P} for a portal.
     */
    private void forgetPreparedObject(String target, String name) {
        if ("S".equals(target)) {
            statementsByName.remove(name);
        } else if ("P".equals(target)) {
            statementsByPortal.remove(name);
        }
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

    /**
     * Number of malformed-frame anomalies observed. Recorded for diagnostics
     * only: bytes are always forwarded and observation continues.
     */
    public long getProtocolAnomalyCount() {
        return protocolAnomalies.get();
    }

    private void recordProtocolAnomaly(String detail) {
        protocolAnomalies.incrementAndGet();
        if (protocolAnomalyLogged.compareAndSet(false, true)) {
            log.warn("PostgreSQL protocol anomaly on session {}: {} (further anomalies are counted only)",
                    sessionId, detail);
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

    /** Sanitized subset of an ErrorResponse/NoticeResponse field list. */
    private record PgErrorFields(String severity, String sqlState) {
    }
}
