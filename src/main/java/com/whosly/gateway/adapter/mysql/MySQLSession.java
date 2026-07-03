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

    private long clientCapabilities;
    private String currentDatabase;
    private int serverSequence;

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
}
