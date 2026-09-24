package com.whosly.gateway.adapter.stub;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.Collection;
import java.util.List;

/**
 * Placeholder for a future Microsoft SQL Server TDS wire {@link ProtocolAdapter}.
 *
 * <p><strong>Not implemented.</strong> Reserved for {@code sqlserver} / {@code mssql}.
 * To add SQL Server: implement TDS framing + session + relay under
 * {@code com.whosly.gateway.adapter.sqlserver}, optional {@code BackendSessionReset},
 * then register on {@link com.whosly.gateway.adapter.ProtocolAdapterRegistry}.</p>
 */
public final class SqlServerProtocolAdapterStub implements ProtocolAdapter {

    private SqlServerProtocolAdapterStub() {
    }

    /**
     * @throws UnsupportedOperationException always — TDS wire protocol is not implemented
     */
    public static ProtocolAdapter unsupported() {
        throw new UnsupportedOperationException(
                "gateway.proxy-db-type='sqlserver' (aliases: mssql) is reserved but not implemented yet; "
                        + "supported today: mysql, postgresql. "
                        + "See ProtocolAdapterRegistry javadoc for how to register a new DB.");
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
