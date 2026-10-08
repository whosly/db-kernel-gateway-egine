package com.whosly.gateway.adapter.stub;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.SqlServerProtocolAdapter;
import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.Collection;
import java.util.List;

/**
 * @deprecated Superseded by {@link SqlServerProtocolAdapter}. Kept only so older
 *             references compile; {@link com.whosly.gateway.adapter.ProtocolAdapterRegistry}
 *             registers the real adapter for {@code sqlserver}/{@code mssql}.
 */
@Deprecated(since = "1.0.0", forRemoval = true)
public final class SqlServerProtocolAdapterStub implements ProtocolAdapter {

    private SqlServerProtocolAdapterStub() {
    }

    /**
     * @deprecated Use {@link SqlServerProtocolAdapter} via the registry.
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static ProtocolAdapter unsupported() {
        throw new UnsupportedOperationException(
                "SqlServerProtocolAdapterStub is deprecated; use gateway.proxy-db-type=sqlserver|mssql "
                        + "which resolves to SqlServerProtocolAdapter.");
    }

    @Override
    public String getProtocolName() {
        return "SQLServer";
    }

    @Override
    public int getDefaultPort() {
        return 1433;
    }

    @Override
    public void start() {
        unsupported();
    }

    @Override
    public void stop() {
        unsupported();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public Collection<ProtocolSession> getActiveSessions() {
        return List.of();
    }
}
