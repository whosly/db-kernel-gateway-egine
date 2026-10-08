package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.DirectBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents MySQL server-first limitation: acquire always sees an empty
 * {@link RoutingContext} because identity arrives only in Handshake Response
 * after the backend greeting (forging a greeting is forbidden).
 */
class MySqlProtocolAdapterRoutingTest {

    @Test
    void acquireUsesEmptyRoutingContextBecauseServerSpeaksFirst() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            List<RoutingContext> acquired = new CopyOnWriteArrayList<>();
            BackendProvider capturingProvider = new BackendProvider() {
                private final BackendProvider delegate = new DirectBackendProvider(
                        InetAddress.getLoopbackAddress().getHostAddress(),
                        targetServer.getLocalPort(),
                        2000);

                @Override
                public Socket acquire() throws IOException {
                    acquired.add(RoutingContext.empty());
                    return delegate.acquire();
                }

                @Override
                public Socket acquire(RoutingContext context) throws IOException {
                    acquired.add(context);
                    return delegate.acquire(context);
                }

                @Override
                public void release(Socket connection) {
                    delegate.release(connection);
                }
            };

            MySqlProtocolAdapter adapter = new MySqlProtocolAdapter() {
                @Override
                protected BackendProvider backendProvider() {
                    return capturingProvider;
                }
            };

            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<?> adapterFuture = executor.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<?> backend = executor.submit(() -> {
                try (Socket target = targetServer.accept()) {
                    byte[] handshake = "mysql-target-handshake".getBytes(StandardCharsets.UTF_8);
                    target.getOutputStream().write(handshake);
                    target.getOutputStream().flush();
                    // Client may never write; close after adapter has acquired.
                    Thread.sleep(200);
                }
                return null;
            });

            // Wait until acquire has happened (backend accepted).
            backend.get(3, TimeUnit.SECONDS);
            clientPair.clientSide.close();
            adapterFuture.get(3, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(acquired).isNotEmpty();
            assertThat(acquired.get(0).isEmpty()).isTrue();
        }
    }

    @Test
    void probeClientForRoutingIsAlwaysEmpty() {
        MySqlProtocolAdapter adapter = new MySqlProtocolAdapter();
        assertThat(adapter.probeClientForRouting(null).context().isEmpty()).isTrue();
        assertThat(adapter.probeClientForRouting(null).hasReplay()).isFalse();
    }

    private record SocketPair(Socket clientSide, Socket serverSide) implements AutoCloseable {
        static SocketPair open() throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                Socket server = listener.accept();
                return new SocketPair(client, server);
            }
        }

        @Override
        public void close() throws Exception {
            clientSide.close();
            serverSide.close();
        }
    }
}
