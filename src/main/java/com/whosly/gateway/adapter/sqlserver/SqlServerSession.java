package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.Optional;

/**
 * SQL Server (TDS) per-client session state.
 *
 * <p>Identity fields are filled from Login7 observation (P1-lite). Last ERROR
 * token fields are filled when a cleartext tabular ERROR is observed (P1).</p>
 */
public class SqlServerSession extends ProtocolSession {

    private String loginUsername;
    private String initialDatabase;
    private int lastPacketType = -1;
    private Integer lastErrorNumber;
    private String lastErrorMessage;

    public SqlServerSession(String connectionId) {
        super("sqlserver", connectionId);
    }

    public Optional<String> getLoginUsername() {
        return Optional.ofNullable(loginUsername);
    }

    public void setLoginUsername(String loginUsername) {
        this.loginUsername = loginUsername;
    }

    public Optional<String> getInitialDatabase() {
        return Optional.ofNullable(initialDatabase);
    }

    public void setInitialDatabase(String initialDatabase) {
        this.initialDatabase = initialDatabase;
    }

    public int getLastPacketType() {
        return lastPacketType;
    }

    public void setLastPacketType(int lastPacketType) {
        this.lastPacketType = lastPacketType;
    }

    public Optional<Integer> getLastErrorNumber() {
        return Optional.ofNullable(lastErrorNumber);
    }

    public void setLastErrorNumber(int lastErrorNumber) {
        this.lastErrorNumber = lastErrorNumber;
    }

    public Optional<String> getLastErrorMessage() {
        return Optional.ofNullable(lastErrorMessage);
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }
}
