package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.net.Socket;

/**
 * Supplies the target connection a client session talks through.
 *
 * <p>The gateway never talks to a database directly outside this contract, so the
 * way backends are obtained can change without touching the per-session flow.
 * Implementations are protocol-agnostic: the same provider (direct, failover, or
 * pooled) serves MySQL, PostgreSQL, and future Oracle / SQL Server adapters.</p>
 *
 * <p>A pooled implementation must additionally:</p>
 * <ul>
 *   <li>refuse to reuse a connection whose
 *       {@link SessionSnapshot#isReusableWithoutReset()} is false;</li>
 *   <li>optionally apply a {@link BackendSessionReset} before reuse; when no
 *       reset SPI is registered, close any socket that is not already proven
 *       clean (default fail-closed strategy — no MySQL/PG-only wire commands
 *       are assumed);</li>
 *   <li>bind the backend later than connection setup when identity-aware routing
 *       is added (future).</li>
 * </ul>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public interface BackendProvider {

    /**
     * Obtains the target connection for one client session.
     *
     * @throws IOException when the target is unreachable
     */
    Socket acquire() throws IOException;

    /**
     * Gives the target connection back without a session snapshot.
     * Direct providers close it; pooled providers treat missing evidence as
     * unsafe and close (fail-closed).
     */
    void release(Socket connection);

    /**
     * Gives the target connection back with observation used for reuse decisions.
     * Default delegates to {@link #release(Socket)} (close). Pooled providers
     * override to consult {@link SessionSnapshot#isReusableWithoutReset()}.
     */
    default void release(Socket connection, SessionSnapshot snapshot) {
        release(connection);
    }
}
