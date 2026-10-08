package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/**
 * Protocol-agnostic {@link BackendProvider} decorator that reuses idle backend
 * sockets via {@link ConfirmedReuseBackendPool}.
 *
 * <p>Not keyed by MySQL vs PostgreSQL: one decorator serves every
 * {@code ProtocolAdapter}. Future Oracle / SQL Server adapters reuse this class
 * unchanged and may optionally register a {@link BackendSessionReset}.</p>
 *
 * <p><strong>Reset strategy:</strong></p>
 * <ul>
 *   <li>Pool only when {@link SessionSnapshot#isReusableWithoutReset()} is true;
 *       otherwise close.</li>
 *   <li>Optional {@link BackendSessionReset} runs only on already-safe sockets;
 *       failure closes.</li>
 *   <li>Default reset is {@link BackendSessionReset#none()} — no protocol-specific
 *       wire command ({@code COM_RESET_CONNECTION} / {@code DISCARD ALL} / etc.).</li>
 *   <li>{@link #release(Socket)} without a snapshot always closes (fail-closed).</li>
 * </ul>
 */
public final class PooledBackendProvider implements BackendProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PooledBackendProvider.class);

    private final ConfirmedReuseBackendPool pool;
    private final BackendSessionReset sessionReset;

    public PooledBackendProvider(BackendProvider factory, int maxIdle) {
        this(factory, maxIdle, BackendSessionReset.none());
    }

    public PooledBackendProvider(BackendProvider factory, int maxIdle, BackendSessionReset sessionReset) {
        this.pool = new ConfirmedReuseBackendPool(factory, maxIdle);
        this.sessionReset = Objects.requireNonNull(sessionReset, "sessionReset must not be null");
    }

    @Override
    public Socket acquire() throws IOException {
        return pool.acquire();
    }

    @Override
    public void release(Socket connection) {
        pool.release(connection, null);
    }

    @Override
    public void release(Socket connection, SessionSnapshot snapshot) {
        if (connection == null) {
            return;
        }
        if (snapshot == null || !snapshot.isReusableWithoutReset()) {
            pool.release(connection, null);
            return;
        }
        try {
            if (!sessionReset.reset(connection, snapshot)) {
                log.debug("Backend session reset declined reuse; closing socket");
                pool.release(connection, null);
                return;
            }
        } catch (IOException e) {
            log.debug("Backend session reset failed; closing socket: {}", e.getMessage());
            pool.release(connection, null);
            return;
        }
        pool.release(connection, snapshot);
    }

    public int idleCount() {
        return pool.idleCount();
    }

    @Override
    public void close() {
        pool.close();
    }
}
