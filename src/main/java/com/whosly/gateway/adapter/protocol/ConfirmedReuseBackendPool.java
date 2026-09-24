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
 * <p>This helper is intentionally <strong>not</strong> wired into
 * {@code AbstractProtocolAdapter} yet: adapters still open one dedicated backend
 * socket per client session. Callers that adopt pooling must pass the session
 * snapshot on release and must still apply an explicit reset strategy before
 * handing a reused socket to a different client identity.</p>
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
