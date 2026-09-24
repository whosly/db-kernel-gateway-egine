package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PooledBackendProviderTest {

    @Test
    void reusesConfirmedCleanNonTransactionSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicInteger opens = new AtomicInteger();
            BackendProvider factory = countingFactory(server, opens);
            try (PooledBackendProvider pooled = new PooledBackendProvider(factory, 2)) {
                Socket first = pooled.acquire();
                assertThat(opens.get()).isEqualTo(1);
                pooled.release(first, reusableSnapshot());
                assertThat(pooled.idleCount()).isEqualTo(1);

                Socket second = pooled.acquire();
                assertThat(opens.get()).isEqualTo(1);
                assertThat(second).isSameAs(first);
            }
        }
    }

    @Test
    void closesDirtyInTransactionAndUntrustedSessionsInsteadOfPooling() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicInteger opens = new AtomicInteger();
            BackendProvider factory = countingFactory(server, opens);
            try (PooledBackendProvider pooled = new PooledBackendProvider(factory, 4)) {
                Socket dirty = pooled.acquire();
                pooled.release(dirty, dirtySnapshot());
                assertThat(pooled.idleCount()).isZero();
                assertThat(dirty.isClosed()).isTrue();

                Socket txn = pooled.acquire();
                pooled.release(txn, inTransactionSnapshot());
                assertThat(pooled.idleCount()).isZero();
                assertThat(txn.isClosed()).isTrue();

                Socket uncertain = pooled.acquire();
                pooled.release(uncertain, uncertainSnapshot());
                assertThat(pooled.idleCount()).isZero();
                assertThat(uncertain.isClosed()).isTrue();

                assertThat(opens.get()).isEqualTo(3);
            }
        }
    }

    @Test
    void releaseWithoutSnapshotCloses() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            BackendProvider factory = countingFactory(server, new AtomicInteger());
            try (PooledBackendProvider pooled = new PooledBackendProvider(factory, 2)) {
                Socket socket = pooled.acquire();
                pooled.release(socket);
                assertThat(pooled.idleCount()).isZero();
                assertThat(socket.isClosed()).isTrue();
            }
        }
    }

    @Test
    void decliningResetClosesSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            BackendProvider factory = countingFactory(server, new AtomicInteger());
            BackendSessionReset declining = (connection, snapshot) -> false;
            try (PooledBackendProvider pooled = new PooledBackendProvider(factory, 2, declining)) {
                Socket socket = pooled.acquire();
                pooled.release(socket, reusableSnapshot());
                assertThat(pooled.idleCount()).isZero();
                assertThat(socket.isClosed()).isTrue();
            }
        }
    }

    private static BackendProvider countingFactory(ServerSocket server, AtomicInteger opens) {
        return new BackendProvider() {
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

    private static SessionSnapshot inTransactionSnapshot() {
        return new SessionSnapshot(
                "AnyProtocol", "s1", ProtocolConnectionState.READY, ObservationConfidence.CONFIRMED,
                true, Optional.empty(), Optional.empty(), SessionDirtiness.clean(),
                Instant.now(), Instant.now());
    }

    private static SessionSnapshot uncertainSnapshot() {
        return new SessionSnapshot(
                "AnyProtocol", "s1", ProtocolConnectionState.READY, ObservationConfidence.UNCERTAIN,
                false, Optional.empty(), Optional.empty(), SessionDirtiness.clean(),
                Instant.now(), Instant.now());
    }
}
