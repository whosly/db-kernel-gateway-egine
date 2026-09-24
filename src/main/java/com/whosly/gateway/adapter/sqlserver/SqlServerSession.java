package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.Optional;

/**
 * SQL Server (TDS) per-client session state.
 *
 * <p>P0 keeps identity fields optional — filled when Login7 observation lands (P1).
 * Transparent relay does not require them.</p>
 */
public class SqlServerSession extends ProtocolSession {

    private String loginUsername;
    private String initialDatabase;
    private int lastPacketType = -1;

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
}
