package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmedReuseBackendPoolTest {

    @Test
    void reusesSocketOnlyWhenSnapshotIsConfirmedCleanAndNotInTransaction() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicInteger opens = new AtomicInteger();
            BackendProvider factory = new BackendProvider() {
                @Override
                public Socket acquire() throws IOException {
                    opens.incrementAndGet();
                    Socket socket = new Socket();
                    socket.connect(server.getLocalSocketAddress());
                    return socket;
                }

                @Override
                public void release(Socket connection) {
                    if (connection != null) {
                        try {
                            connection.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            };
            ConfirmedReuseBackendPool pool = new ConfirmedReuseBackendPool(factory, 2);

            Socket first = pool.acquire();
            assertThat(opens.get()).isEqualTo(1);
            pool.release(first, reusableSnapshot());
            assertThat(pool.idleCount()).isEqualTo(1);

            Socket second = pool.acquire();
            assertThat(opens.get()).isEqualTo(1);
            assertThat(second).isSameAs(first);
            pool.release(second, dirtySnapshot());
            assertThat(pool.idleCount()).isZero();
            assertThat(second.isClosed()).isTrue();

            pool.acquire();
            assertThat(opens.get()).isEqualTo(2);
            pool.close();
        }
    }

    @Test
    void closesInsteadOfPoolingWhenIdleCapReached() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            BackendProvider factory = new DirectBackendProvider("127.0.0.1", server.getLocalPort(), 500);
            ConfirmedReuseBackendPool pool = new ConfirmedReuseBackendPool(factory, 0);
            Socket socket = pool.acquire();
            pool.release(socket, reusableSnapshot());
            assertThat(pool.idleCount()).isZero();
            assertThat(socket.isClosed()).isTrue();
        }
    }

    private static SessionSnapshot reusableSnapshot() {
        return new SessionSnapshot(
                "MySQL", "s1", ProtocolConnectionState.READY, ObservationConfidence.CONFIRMED,
                false, Optional.empty(), Optional.empty(), SessionDirtiness.clean(),
                Instant.now(), Instant.now());
    }

    private static SessionSnapshot dirtySnapshot() {
        return new SessionSnapshot(
                "MySQL", "s1", ProtocolConnectionState.READY, ObservationConfidence.CONFIRMED,
                false, Optional.empty(), Optional.empty(),
                new SessionDirtiness(true, false, false, false, false, false),
                Instant.now(), Instant.now());
    }
}
