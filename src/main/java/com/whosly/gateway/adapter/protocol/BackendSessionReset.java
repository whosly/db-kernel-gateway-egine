package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.net.Socket;

/**
 * Optional protocol-specific reset applied before a pooled backend socket is
 * reused by a later client session.
 *
 * <p>Protocol-agnostic SPI: MySQL ({@code COM_RESET_CONNECTION}), PostgreSQL
 * ({@code DISCARD ALL}), Oracle, SQL Server, or any future adapter may register
 * an implementation. The default gateway strategy does <strong>not</strong>
 * depend on a wire reset — see {@link #none()}.</p>
 *
 * <p><strong>Default safe behaviour:</strong> {@link PooledBackendProvider} only
 * considers reuse when {@link SessionSnapshot#isReusableWithoutReset()} is true
 * ({@code CONFIRMED} + clean + not in a transaction). Unsafe sockets are closed.
 * A registered reset is an extra step for sockets already proven clean; if reset
 * returns {@code false} or throws, the socket is closed.</p>
 */
@FunctionalInterface
public interface BackendSessionReset {

    /**
     * Attempts to clear residual session state on {@code connection}.
     *
     * @param connection backend socket about to return to the idle pool
     * @param snapshot   observation that already satisfied
     *                   {@link SessionSnapshot#isReusableWithoutReset()}
     * @return {@code true} when the socket may be pooled; {@code false} to close it
     * @throws IOException when the reset attempt fails hard (caller closes)
     */
    boolean reset(Socket connection, SessionSnapshot snapshot) throws IOException;

    /**
     * No wire reset: pooling relies solely on
     * {@link SessionSnapshot#isReusableWithoutReset()}. Safe default for every
     * protocol, including ones that have not implemented a reset command yet.
     */
    static BackendSessionReset none() {
        return (connection, snapshot) -> true;
    }
}
