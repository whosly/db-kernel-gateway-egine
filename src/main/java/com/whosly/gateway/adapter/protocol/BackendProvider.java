package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.net.Socket;

/**
 * Supplies the target connection a client session talks through.
 *
 * <p>The gateway never talks to a database directly outside this contract, so the
 * way backends are obtained can change without touching the per-session flow:
 * today each session opens its own connection and closes it when the session
 * ends.</p>
 *
 * <p>A pooled implementation must additionally:</p>
 * <ul>
 *   <li>refuse to reuse a connection whose
 *       {@link SessionSnapshot#isReusableWithoutReset()} is false;</li>
 *   <li>apply an explicit reset strategy ({@code COM_RESET_CONNECTION} /
 *       {@code DISCARD ALL}) before handing a connection to another client, and
 *       destroy it when a reset cannot be proven;</li>
 *   <li>bind the backend later than connection setup, because the client identity
 *       and database are only known after the handshake.</li>
 * </ul>
 * <p>Those changes are a later phase, so this contract deliberately stays minimal
 * today.</p>
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
     * Gives the target connection back. A direct provider closes it; a pooled
     * provider decides between reuse, reset and destruction.
     */
    void release(Socket connection);
}
