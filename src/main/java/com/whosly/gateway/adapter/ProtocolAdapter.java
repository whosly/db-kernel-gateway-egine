package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.ProtocolSession;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;

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

    /**
     * Consumable view of the sessions currently proxied by this adapter.
     *
     * <p>Unlike {@link #getActiveSessions()}, the snapshot carries no mutable
     * protocol internals: it exposes the observed state together with how much
     * that observation can be trusted, whether a transaction is open, and which
     * session state would leak into a later client. Auditing, risk control,
     * routing and connection pooling should consume this instead (rule 2.10/8.3).</p>
     *
     * @return immutable snapshot list of active sessions
     */
    default Collection<SessionSnapshot> getActiveSessionSnapshots() {
        return getActiveSessions().stream()
                .map(ProtocolSession::snapshot)
                .toList();
    }

    /**
     * Close the client-leg socket for {@code connectionId} (proxy control-plane KILL).
     *
     * <p>Default: unsupported. {@code AbstractProtocolAdapter} closes the mapped client
     * socket so the relay ends; it does <em>not</em> send a protocol-level KILL to the
     * backend. Returns {@code false} when the id is unknown.</p>
     *
     * @param connectionId gateway session id from {@link SessionSnapshot#connectionId()}
     * @return true if a client socket was found and closed
     */
    default boolean killClientSession(String connectionId) {
        return false;
    }
}