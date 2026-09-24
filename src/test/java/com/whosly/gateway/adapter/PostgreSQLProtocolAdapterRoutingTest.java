package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendEndpoint;
import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.DirectBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import com.whosly.gateway.adapter.protocol.RoutingRule;
import com.whosly.gateway.adapter.protocol.WeightedEndpoint;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves PostgreSQL peeks StartupMessage and acquires with a non-empty
 * {@link RoutingContext} so match-database / match-username rules can fire.
 */
class PostgreSQLProtocolAdapterRoutingTest {

    @Test
    void adapterPassesStartupIdentityIntoBackendProviderAcquire() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            List<RoutingContext> acquired = new CopyOnWriteArrayList<>();
            BackendProvider capturingProvider = capturing(
                    new DirectBackendProvider(
                            InetAddress.getLoopbackAddress().getHostAddress(),
                            targetServer.getLocalPort(),
                            2000),
                    acquired);

            PostgreSQLProtocolAdapter adapter = new PostgreSQLProtocolAdapter() {
                @Override
                protected BackendProvider backendProvider() {
                    return capturingProvider;
                }
            };

            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<?> adapterFuture = executor.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendBytes = executor.submit(() -> {
                try (Socket target = targetServer.accept()) {
                    byte[] expected = startupMessage("user", "carol", "database", "analytics");
                    byte[] observed = readExact(target.getInputStream(), expected.length);
                    target.getOutputStream().write(new byte[]{'R'});
                    target.getOutputStream().flush();
                    return observed;
                }
            });

            byte[] startup = startupMessage("user", "carol", "database", "analytics");
            clientPair.clientSide.getOutputStream().write(startup);
            clientPair.clientSide.getOutputStream().flush();

            assertThat(backendBytes.get(3, TimeUnit.SECONDS)).isEqualTo(startup);
            clientPair.clientSide.close();
            adapterFuture.get(3, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(acquired).hasSize(1);
            assertThat(acquired.get(0).isEmpty()).isFalse();
            assertThat(acquired.get(0)).isEqualTo(RoutingContext.of("carol", "analytics"));
        }
    }

    @Test
    void matchDatabaseAndUsernameRuleSelectsRoutedBackend() throws Exception {
        try (ServerSocket fallbackServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket routedServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            String loopback = InetAddress.getLoopbackAddress().getHostAddress();
            AtomicBoolean fallbackAccepted = new AtomicBoolean(false);

            RoutingRule rule = new RoutingRule("shop", "alice",
                    List.of(new WeightedEndpoint(new BackendEndpoint(loopback, routedServer.getLocalPort()), 1)));

            PostgreSQLProtocolAdapter adapter = new PostgreSQLProtocolAdapter();
            adapter.setTargetHost(loopback);
            adapter.setTargetPort(fallbackServer.getLocalPort());
            adapter.setBackendEndpoints(List.of(new BackendEndpoint(loopback, fallbackServer.getLocalPort())));
            adapter.setRoutingEnabled(true);
            adapter.setRoutingRules(List.of(rule));

            ExecutorService executor = Executors.newFixedThreadPool(3);
            Future<?> adapterFuture = executor.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<?> fallbackWatch = executor.submit(() -> {
                try (Socket ignored = fallbackServer.accept()) {
                    fallbackAccepted.set(true);
                } catch (IOException ignored) {
                    // closed when test ends
                }
            });
            Future<Socket> routedAccept = executor.submit(routedServer::accept);

            byte[] startup = startupMessage("user", "alice", "database", "shop");
            clientPair.clientSide.getOutputStream().write(startup);
            clientPair.clientSide.getOutputStream().flush();

            try (Socket routedSocket = routedAccept.get(3, TimeUnit.SECONDS)) {
                byte[] observed = readExact(routedSocket.getInputStream(), startup.length);
                assertThat(observed).isEqualTo(startup);
                routedSocket.getOutputStream().write(new byte[]{'R'});
                routedSocket.getOutputStream().flush();
            }

            clientPair.clientSide.close();
            adapterFuture.get(3, TimeUnit.SECONDS);
            fallbackServer.close();
            fallbackWatch.cancel(true);
            executor.shutdownNow();

            assertThat(fallbackAccepted).isFalse();
        }
    }

    private static BackendProvider capturing(BackendProvider delegate, List<RoutingContext> sink) {
        return new BackendProvider() {
            @Override
            public Socket acquire() throws IOException {
                sink.add(RoutingContext.empty());
                return delegate.acquire();
            }

            @Override
            public Socket acquire(RoutingContext context) throws IOException {
                sink.add(context);
                return delegate.acquire(context);
            }

            @Override
            public void release(Socket connection) {
                delegate.release(connection);
            }
        };
    }

    private static byte[] startupMessage(String... keyValues) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(intBytes(196608));
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            body.writeBytes(cstring(keyValues[index]));
            body.writeBytes(cstring(keyValues[index + 1]));
        }
        body.write(0);
        byte[] payload = body.toByteArray();
        return concat(intBytes(payload.length + 4), payload);
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static byte[] cstring(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    private static byte[] readExact(InputStream inputStream, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inputStream.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new AssertionError("Unexpected EOF");
            }
            offset += count;
        }
        return bytes;
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
