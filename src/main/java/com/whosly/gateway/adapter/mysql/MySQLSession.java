package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.Optional;

/**
 * MySQL-specific per-client session state.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MySQLSession extends ProtocolSession {

    /**
     * Client-visible transaction state decoded from OK/EOF status flags.
     */
    public enum TransactionStatus {
        IDLE,
        IN_TRANSACTION
    }

    /** Client capability flags observed in the handshake response. */
    private long clientCapabilities;
    /** Database selected by CLIENT_CONNECT_WITH_DB or COM_INIT_DB. */
    private String currentDatabase;
    /** Outgoing server packet sequence counter (used by tests and future writers). */
    private int serverSequence;
    /** Client-visible transaction state, decoded from OK/EOF status flags. */
    private TransactionStatus transactionStatus = TransactionStatus.IDLE;
    /** True while SERVER_STATUS_AUTOCOMMIT is set. */
    private boolean autocommit = true;
    /** Affected rows reported by the last OK packet. */
    private long lastAffectedRows;
    /** Warning count reported by the last OK/EOF packet. */
    private int lastWarningCount;
    /** SQLSTATE reported by the last ERR packet. */
    private String lastSqlState;

    public MySQLSession(String connectionId) {
        super("mysql", connectionId);
    }

    public long getClientCapabilities() {
        return clientCapabilities;
    }

    public void setClientCapabilities(long clientCapabilities) {
        this.clientCapabilities = clientCapabilities;
    }

    public Optional<String> getCurrentDatabase() {
        return Optional.ofNullable(currentDatabase);
    }

    public void setCurrentDatabase(String currentDatabase) {
        this.currentDatabase = currentDatabase;
    }

    public int nextServerSequence() {
        serverSequence = (serverSequence + 1) & 0xFF;
        return serverSequence;
    }

    public void resetSequence() {
        serverSequence = 0;
    }

    /**
     * Applies the {@code status_flags} observed on an OK/EOF packet, keeping the
     * transaction state real instead of hard-coded.
     */
    public void applyStatusFlags(int statusFlags) {
        this.transactionStatus = (statusFlags & MySQLServerStatusFlag.SERVER_STATUS_IN_TRANS.getFlag()) != 0
                ? TransactionStatus.IN_TRANSACTION
                : TransactionStatus.IDLE;
        this.autocommit = (statusFlags & MySQLServerStatusFlag.SERVER_STATUS_AUTOCOMMIT.getFlag()) != 0;
    }

    public TransactionStatus getTransactionStatus() {
        return transactionStatus;
    }

    public boolean isAutocommit() {
        return autocommit;
    }

    public long getLastAffectedRows() {
        return lastAffectedRows;
    }

    public void setLastAffectedRows(long lastAffectedRows) {
        this.lastAffectedRows = lastAffectedRows;
    }

    public int getLastWarningCount() {
        return lastWarningCount;
    }

    public void setLastWarningCount(int lastWarningCount) {
        this.lastWarningCount = lastWarningCount;
    }

    public Optional<String> getLastSqlState() {
        return Optional.ofNullable(lastSqlState);
    }

    public void setLastSqlState(String lastSqlState) {
        this.lastSqlState = lastSqlState;
    }
}
