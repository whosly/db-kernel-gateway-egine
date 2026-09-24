package com.whosly.gateway.adapter.stub;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.Collection;
import java.util.List;

/**
 * Placeholder for a future Oracle TNS / Net8 wire {@link ProtocolAdapter}.
 *
 * <p><strong>Not implemented.</strong> Reserved so {@code gateway.proxy-db-type=oracle}
 * fails with a clear message instead of silently mapping to MySQL/PostgreSQL.
 * To add Oracle: implement framing + session + relay under
 * {@code com.whosly.gateway.adapter.oracle}, optional {@code BackendSessionReset},
 * then {@code registry.register("oracle", OracleProtocolAdapter::new, …)}.</p>
 */
public final class OracleProtocolAdapterStub implements ProtocolAdapter {

    private OracleProtocolAdapterStub() {
    }

    /**
     * @throws UnsupportedOperationException always — Oracle wire protocol is not implemented
     */
    public static ProtocolAdapter unsupported() {
        throw new UnsupportedOperationException(
                "gateway.proxy-db-type='oracle' is reserved but not implemented yet; "
                        + "supported today: mysql, postgresql. "
                        + "See ProtocolAdapterRegistry javadoc for how to register a new DB.");
    }

    @Override
    public String getProtocolName() {
        return "Oracle";
    }

    @Override
    public int getDefaultPort() {
        return 1521;
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
