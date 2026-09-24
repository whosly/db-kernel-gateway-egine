package com.whosly.gateway.adapter;

/**
 * Creates a configured {@link ProtocolAdapter} instance for one database type.
 *
 * <p>Factories are registered on {@link ProtocolAdapterRegistry} keyed by
 * {@code gateway.proxy-db-type}. Built-ins cover MySQL and PostgreSQL; additional
 * databases (Oracle, SQL Server, …) register the same way without editing a
 * string switch in {@code GatewayConfig}.</p>
 */
@FunctionalInterface
public interface ProtocolAdapterFactory {

    /**
     * @return a new adapter instance (not yet started)
     * @throws UnsupportedOperationException when the db type is reserved but unimplemented
     */
    ProtocolAdapter create();
}
