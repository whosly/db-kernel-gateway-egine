package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerProtocolAdapterTest {

    @Test
    void adapterCreationDefaults() {
        SqlServerProtocolAdapter adapter = new SqlServerProtocolAdapter();
        assertThat(adapter.getProtocolName()).isEqualTo("SQLServer");
        assertThat(adapter.getDefaultPort()).isEqualTo(31433);
        assertThat(adapter.isRunning()).isFalse();
    }

    @Test
    void startAndStopOnEphemeralPort() {
        SqlServerProtocolAdapter adapter = new SqlServerProtocolAdapter();
        adapter.setPort(0);
        // Bind via start() uses configured port; use high unused port from OS by opening ServerSocket first
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = probe.getLocalPort();
            probe.close();
            adapter.setPort(port);
            adapter.start();
            assertThat(adapter.isRunning()).isTrue();
            adapter.stop();
            assertThat(adapter.isRunning()).isFalse();
        } catch (Exception e) {
            adapter.stop();
            throw new AssertionError(e);
        }
    }

    @Test
    void handleClientConnectionTransparentlyRelaysBytes() throws Exception {
        try (ServerSocket targetServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {
            ExecutorService executorService = Executors.newFixedThreadPool(2);
            SqlServerProtocolAdapter adapter = new SqlServerProtocolAdapter();
            adapter.setTargetHost(InetAddress.getLoopbackAddress().getHostAddress());
            adapter.setTargetPort(targetServer.getLocalPort());

            Future<?> adapterFuture = executorService.submit(() -> adapter.handleClientConnection(clientPair.serverSide));
            Future<byte[]> backendObservedClientBytes = executorService.submit(() -> {
                try (Socket targetSocket = targetServer.accept()) {
                    byte[] greeting = "tds-target-prelogin-response".getBytes(StandardCharsets.UTF_8);
                    targetSocket.getOutputStream().write(greeting);
                    targetSocket.getOutputStream().flush();
                    return readFully(targetSocket.getInputStream(), "client-prelogin".length());
                }
            });

            clientPair.clientSide.getOutputStream().write("client-prelogin".getBytes(StandardCharsets.UTF_8));
            clientPair.clientSide.getOutputStream().flush();
            byte[] clientObserved = readFully(clientPair.clientSide.getInputStream(),
                    "tds-target-prelogin-response".length());

            assertThat(new String(clientObserved, StandardCharsets.UTF_8))
                    .isEqualTo("tds-target-prelogin-response");
            assertThat(new String(backendObservedClientBytes.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8))
                    .isEqualTo("client-prelogin");

            clientPair.clientSide.close();
            adapterFuture.get(5, TimeUnit.SECONDS);
            executorService.shutdownNow();
        }
    }

    @Test
    void registryResolvesSqlServerAndMssql() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.withBuiltIns();
        assertThat(registry.create("sqlserver")).isInstanceOf(SqlServerProtocolAdapter.class);
        assertThat(registry.create("mssql")).isInstanceOf(SqlServerProtocolAdapter.class);
        assertThat(registry.createSessionReset("sqlserver")).isSameAs(BackendSessionReset.NONE);
        assertThat(registry.createSessionReset("mssql")).isSameAs(BackendSessionReset.NONE);
    }

    private static byte[] readFully(InputStream inputStream, int length) throws Exception {
        byte[] buffer = new byte[length];
        int read = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (read < length) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out reading " + length + " bytes, got " + read);
            }
            int n = inputStream.read(buffer, read, length - read);
            if (n < 0) {
                throw new AssertionError("EOF after " + read + " of " + length);
            }
            read += n;
        }
        return buffer;
    }

    /** Local helper mirroring MySQL/PG adapter tests. */
    private static final class SocketPair implements AutoCloseable {
        final Socket clientSide;
        final Socket serverSide;

        private SocketPair(Socket clientSide, Socket serverSide) {
            this.clientSide = clientSide;
            this.serverSide = serverSide;
        }

        static SocketPair open() throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Socket client = new Socket();
                client.connect(listener.getLocalSocketAddress());
                Socket server = listener.accept();
                return new SocketPair(client, server);
            }
        }

        @Override
        public void close() {
            try {
                clientSide.close();
            } catch (Exception ignored) {
            }
            try {
                serverSide.close();
            } catch (Exception ignored) {
            }
        }
    }
}
