package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.ProtocolSession;

import java.util.Collection;

/**
 * Interface for protocol adapters that handle different database protocols.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
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

    /**
     * Snapshot of the sessions currently proxied by this adapter.
     *
     * <p>Each session exposes the protocol state the gateway observed on the
     * wire, such as transaction status, selected database and backend metadata.
     * The returned collection is a copy; later changes are not reflected.</p>
     *
     * @return immutable snapshot of active sessions
     */
    Collection<ProtocolSession> getActiveSessions();
}