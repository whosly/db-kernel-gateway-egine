package com.whosly.gateway.adapter;

/**
 * Interface for protocol adapters that handle different database protocols.
 */
public interface ProtocolAdapter {

    /**
     * Get the name of the protocol handled by this adapter.
     *
     * @return protocol name
     */
    String getProtocolName();

    /**
     * Get the default port for this protocol.
     *
     * @return default port number
     */
    int getDefaultPort();

    /**
     * Start the protocol adapter and begin listening for connections.
     */
    void start();

    /**
     * Stop the protocol adapter and close all connections.
     */
    void stop();

    /**
     * Check if the adapter is currently running.
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();
}