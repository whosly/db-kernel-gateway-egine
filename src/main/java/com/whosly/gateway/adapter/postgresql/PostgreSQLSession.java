package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL-specific per-client session state.
 *
 * <p>Holds what the gateway observed on the wire: startup parameters, the
 * client-visible transaction status from {@code ReadyForQuery}, and metadata of
 * the last backend response. Backend statements and portals stay on the server;
 * only what is visible in cleartext traffic is mirrored here.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLSession extends ProtocolSession {

    /**
     * Transaction status reported by {@code ReadyForQuery}.
     */
    public enum TransactionStatus {
        /** {@code I}: idle, no transaction in progress. */
        IDLE('I'),
        /** {@code T}: a transaction block is open. */
        IN_TRANSACTION('T'),
        /** {@code E}: the transaction block failed and waits for rollback. */
        FAILED_TRANSACTION('E');

        private final char wireCode;

        TransactionStatus(char wireCode) {
            this.wireCode = wireCode;
        }

        public char getWireCode() {
            return wireCode;
        }
    }

    /** Startup parameters observed in the client StartupMessage. */
    private final Map<String, String> parameters = new HashMap<>();
    /** Prepared statements seen in Parse, keyed by statement name. */
    private final Map<String, String> preparedStatements = new HashMap<>();
    /** Latest transaction status from ReadyForQuery. */
    private TransactionStatus transactionStatus = TransactionStatus.IDLE;
    /** Field count of the last RowDescription; -1 when none was seen. */
    private int lastRowDescriptionFieldCount = -1;
    /** Parameter count of the last ParameterDescription; -1 when none was seen. */
    private int lastParameterDescriptionCount = -1;
    /** Command tag of the last CommandComplete. */
    private String lastCommandTag;
    /** SQLSTATE of the last ErrorResponse. */
    private String lastSqlState;
    /** Severity of the last ErrorResponse. */
    private String lastSeverity;
    /** Severity of the last NoticeResponse. */
    private String lastNoticeSeverity;
    /** SQLSTATE of the last NoticeResponse. */
    private String lastNoticeSqlState;
    /** Backend process id from BackendKeyData; -1 when not seen. */
    private int backendProcessId = -1;
    /** Cancel secret from BackendKeyData; kept in memory only and never logged. */
    private int backendSecretKey;
    /** Authentication type of the last Authentication request; -1 when not seen. */
    private int lastAuthenticationType = -1;
    /** {@code DataRow} messages observed in the last result set. */
    private long lastResultRowCount;
    /** {@code CopyData} messages observed in the last COPY operation. */
    private long lastCopyDataCount;
    /** Backend {@code ParseComplete} messages observed. */
    private long parseCompleteCount;
    /** Backend {@code BindComplete} messages observed. */
    private long bindCompleteCount;
    /** Backend {@code CloseComplete} messages observed. */
    private long closeCompleteCount;
    /** Backend {@code NoData} messages observed (Describe with no result set). */
    private long noDataCount;
    /** Backend {@code PortalSuspended} messages observed (portal row limit reached). */
    private long portalSuspendedCount;
    /** True when Sync was sent and its ReadyForQuery has not arrived yet. */
    private boolean syncPending;
    /** Sync round trips observed. */
    private long syncCount;
    /** Backend process id of the last NotificationResponse; -1 when none was seen. */
    private int lastNotificationProcessId = -1;
    /** Channel of the last NotificationResponse; its payload is never retained. */
    private String lastNotificationChannel;

    public PostgreSQLSession(String connectionId) {
        super("postgresql", connectionId);
    }

    public void setParameter(String name, String value) {
        parameters.put(name, value);
    }

    public Optional<String> getParameter(String name) {
        return Optional.ofNullable(parameters.get(name));
    }

    public void setTransactionStatus(TransactionStatus transactionStatus) {
        this.transactionStatus = transactionStatus;
        setInTransaction(transactionStatus != TransactionStatus.IDLE);
    }

    public char getReadyForQueryStatus() {
        return transactionStatus.getWireCode();
    }

    public void putPreparedStatement(String name, String sql) {
        preparedStatements.put(name, sql);
    }

    public Optional<String> getPreparedStatement(String name) {
        return Optional.ofNullable(preparedStatements.get(name));
    }

    /**
     * Field count observed on the last backend {@code RowDescription}.
     */
    public int getLastRowDescriptionFieldCount() {
        return lastRowDescriptionFieldCount;
    }

    public void setLastRowDescriptionFieldCount(int lastRowDescriptionFieldCount) {
        this.lastRowDescriptionFieldCount = lastRowDescriptionFieldCount;
    }

    /**
     * Parameter count observed on the last backend {@code ParameterDescription}.
     */
    public int getLastParameterDescriptionCount() {
        return lastParameterDescriptionCount;
    }

    public void setLastParameterDescriptionCount(int lastParameterDescriptionCount) {
        this.lastParameterDescriptionCount = lastParameterDescriptionCount;
    }

    /**
     * Command tag observed on the last backend {@code CommandComplete}.
     */
    public Optional<String> getLastCommandTag() {
        return Optional.ofNullable(lastCommandTag);
    }

    public void setLastCommandTag(String lastCommandTag) {
        this.lastCommandTag = lastCommandTag;
    }

    /**
     * SQLSTATE observed on the last backend {@code ErrorResponse}.
     */
    public Optional<String> getLastSqlState() {
        return Optional.ofNullable(lastSqlState);
    }

    public void setLastSqlState(String lastSqlState) {
        this.lastSqlState = lastSqlState;
    }

    /**
     * Severity observed on the last backend {@code ErrorResponse}.
     */
    public Optional<String> getLastSeverity() {
        return Optional.ofNullable(lastSeverity);
    }

    public void setLastSeverity(String lastSeverity) {
        this.lastSeverity = lastSeverity;
    }

    /**
     * Severity of the last backend {@code NoticeResponse}.
     */
    public Optional<String> getLastNoticeSeverity() {
        return Optional.ofNullable(lastNoticeSeverity);
    }

    public void setLastNoticeSeverity(String lastNoticeSeverity) {
        this.lastNoticeSeverity = lastNoticeSeverity;
    }

    /**
     * SQLSTATE of the last backend {@code NoticeResponse}.
     */
    public Optional<String> getLastNoticeSqlState() {
        return Optional.ofNullable(lastNoticeSqlState);
    }

    public void setLastNoticeSqlState(String lastNoticeSqlState) {
        this.lastNoticeSqlState = lastNoticeSqlState;
    }

    /**
     * Backend process id from {@code BackendKeyData}, used to associate a future
     * cancel request with this session.
     */
    public int getBackendProcessId() {
        return backendProcessId;
    }

    public void setBackendProcessId(int backendProcessId) {
        this.backendProcessId = backendProcessId;
    }

    /**
     * Cancel secret from {@code BackendKeyData}. It is kept in memory only and
     * never logged.
     */
    public int getBackendSecretKey() {
        return backendSecretKey;
    }

    public void setBackendSecretKey(int backendSecretKey) {
        this.backendSecretKey = backendSecretKey;
    }

    /**
     * Authentication type int4 of the last {@code Authentication} request
     * (for example 0 = OK, 3 = cleartext, 5 = MD5, 10 = SASL).
     */
    public int getLastAuthenticationType() {
        return lastAuthenticationType;
    }

    public void setLastAuthenticationType(int lastAuthenticationType) {
        this.lastAuthenticationType = lastAuthenticationType;
    }

    /**
     * Starts observation of a new result set, resetting the {@code DataRow}
     * counter. The gateway counts rows (rule 4.8) but never rewrites them.
     */
    public void beginResultSet() {
        this.lastResultRowCount = 0;
    }

    /** Counts one {@code DataRow} message of the current result set. */
    public void incrementResultRows() {
        this.lastResultRowCount++;
    }

    /** {@code DataRow} messages observed in the last result set. */
    public long getLastResultRowCount() {
        return lastResultRowCount;
    }

    /**
     * Starts observation of a new COPY operation, resetting the {@code CopyData}
     * counter (rule 4.6).
     */
    public void beginCopy() {
        this.lastCopyDataCount = 0;
    }

    /** Counts one {@code CopyData} message of the current COPY operation. */
    public void incrementCopyData() {
        this.lastCopyDataCount++;
    }

    /** {@code CopyData} messages observed in the last COPY operation. */
    public long getLastCopyDataCount() {
        return lastCopyDataCount;
    }

    /** Records a backend {@code ParseComplete} (rule 4.7). */
    public void recordParseComplete() {
        parseCompleteCount++;
    }

    public long getParseCompleteCount() {
        return parseCompleteCount;
    }

    /** Records a backend {@code BindComplete}. */
    public void recordBindComplete() {
        bindCompleteCount++;
    }

    public long getBindCompleteCount() {
        return bindCompleteCount;
    }

    /** Records a backend {@code CloseComplete}. */
    public void recordCloseComplete() {
        closeCompleteCount++;
    }

    public long getCloseCompleteCount() {
        return closeCompleteCount;
    }

    /** Records a backend {@code NoData}: Describe found no result set. */
    public void recordNoData() {
        noDataCount++;
    }

    public long getNoDataCount() {
        return noDataCount;
    }

    /** Records a backend {@code PortalSuspended}: the portal hit its row limit. */
    public void recordPortalSuspended() {
        portalSuspendedCount++;
    }

    public long getPortalSuspendedCount() {
        return portalSuspendedCount;
    }

    /**
     * Marks a client {@code Sync}.
     *
     * <p>{@code Sync} is the extended-query error-recovery and ReadyForQuery
     * synchronisation point (rule 4.7): after an error the server discards
     * messages until this marker, then answers ReadyForQuery.</p>
     */
    public void markSyncRequested() {
        syncPending = true;
        syncCount++;
    }

    /** Marks the {@code ReadyForQuery} that answers the pending {@code Sync}. */
    public void markSyncCompleted() {
        syncPending = false;
    }

    /** True while a {@code Sync} is still waiting for its {@code ReadyForQuery}. */
    public boolean isSyncPending() {
        return syncPending;
    }

    /** Sync round trips observed. */
    public long getSyncCount() {
        return syncCount;
    }

    /**
     * Records which backend session sent a {@code NotificationResponse}
     * (LISTEN/NOTIFY). Only the process id and channel name are kept:
     * notification payloads are application data and are never retained.
     */
    public void recordNotification(int processId, String channel) {
        this.lastNotificationProcessId = processId;
        this.lastNotificationChannel = channel;
    }

    /** Backend process id of the last NotificationResponse; -1 when none was seen. */
    public int getLastNotificationProcessId() {
        return lastNotificationProcessId;
    }

    /** Channel of the last NotificationResponse. */
    public Optional<String> getLastNotificationChannel() {
        return Optional.ofNullable(lastNotificationChannel);
    }
}
