package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minimal idle-socket pool that reuses a backend connection only when observation
 * proves it is safe: {@link SessionSnapshot#isReusableWithoutReset()} must be true
 * ({@code CONFIRMED}, not in a transaction, dirtiness clean).
 *
 * <p>Wired through {@link PooledBackendProvider} when {@code gateway.pool.enabled}
 * is true. The pool is protocol-agnostic (shared by every {@code ProtocolAdapter}).
 * Reuse requires {@link SessionSnapshot#isReusableWithoutReset()}; otherwise the
 * socket is closed. Optional {@link BackendSessionReset} hooks may run after that
 * check — the default is close-if-unsafe with no wire reset.</p>
 */
public final class ConfirmedReuseBackendPool {

    private final BackendProvider factory;
    private final ConcurrentLinkedQueue<Socket> idle = new ConcurrentLinkedQueue<>();
    private final int maxIdle;

    public ConfirmedReuseBackendPool(BackendProvider factory, int maxIdle) {
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
        if (maxIdle < 0) {
            throw new IllegalArgumentException("maxIdle must not be negative");
        }
        this.maxIdle = maxIdle;
    }

    /**
     * Returns an idle open socket when one exists, otherwise opens a new one via
     * the factory.
     */
    public Socket acquire() throws IOException {
        Socket recycled;
        while ((recycled = idle.poll()) != null) {
            if (!recycled.isClosed() && recycled.isConnected()) {
                return recycled;
            }
            closeQuietly(recycled);
        }
        return factory.acquire();
    }

    /**
     * Returns a socket to the idle pool only when the snapshot proves reuse is
     * safe; otherwise closes it.
     */
    public void release(Socket connection, SessionSnapshot snapshot) {
        if (connection == null) {
            return;
        }
        if (snapshot == null || !snapshot.isReusableWithoutReset()
                || connection.isClosed() || !connection.isConnected()
                || idle.size() >= maxIdle) {
            closeQuietly(connection);
            return;
        }
        idle.offer(connection);
    }

    /** Number of sockets currently sitting idle. */
    public int idleCount() {
        return idle.size();
    }

    /** Closes every idle socket. */
    public void close() {
        Socket socket;
        while ((socket = idle.poll()) != null) {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
