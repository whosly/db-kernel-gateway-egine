package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL-specific per-client session state.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLSession extends ProtocolSession {

    public enum TransactionStatus {
        IDLE('I'),
        IN_TRANSACTION('T'),
        FAILED_TRANSACTION('E');

        private final char wireCode;

        TransactionStatus(char wireCode) {
            this.wireCode = wireCode;
        }

        public char getWireCode() {
            return wireCode;
        }
    }

    private final Map<String, String> parameters = new HashMap<>();
    private final Map<String, String> preparedStatements = new HashMap<>();
    private TransactionStatus transactionStatus = TransactionStatus.IDLE;

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
}
