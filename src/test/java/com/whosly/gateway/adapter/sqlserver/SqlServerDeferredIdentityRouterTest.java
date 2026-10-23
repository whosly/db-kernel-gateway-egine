package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.BackendEndpoint;
import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.DirectBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import com.whosly.gateway.adapter.protocol.RoutingRule;
import com.whosly.gateway.adapter.protocol.WeightedEndpoint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerDeferredIdentityRouterTest {

    @Test
    void matchPathReplaysLogin7OnBootstrap() throws Exception {
        byte[] preLoginPayload = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_OFF);
        byte[] login7Payload = Login7ObservationTest.buildLogin7Payload("fallback_user", "master", "app", "host");

        try (ServerSocket bootstrapServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<?> backend = pool.submit(() -> {
                try (Socket s = bootstrapServer.accept()) {
                    TdsLogicalMessage pre = TdsLogicalMessage.read(s.getInputStream());
                    assertThat(pre.type()).isEqualTo(TdsPacketType.PRELOGIN.getCode());
                    TdsLogicalMessage.singlePacket(
                                    TdsPacketType.PRELOGIN.getCode(), preLoginPayload, 1)
                            .writeTo(s.getOutputStream());
                    // Match path: Login7 is only written after route returns via DuplexRelay prefix;
                    // keep socket open briefly so acquire stays valid.
                    Thread.sleep(400);
                }
                return null;
            });

            BackendProvider fallback = new DirectBackendProvider(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    bootstrapServer.getLocalPort(), 2000);
            RoutingBackendProvider routing = new RoutingBackendProvider(List.of(), fallback);

            Socket bootstrap = routing.acquire(RoutingContext.empty());
            Future<SqlServerDeferredIdentityRouter.Result> routeFuture = pool.submit(() ->
                    SqlServerDeferredIdentityRouter.route(
                            clientPair.serverSide, bootstrap, routing, Socket::close));

            try (Socket client = clientPair.clientSide) {
                TdsLogicalMessage.singlePacket(
                                TdsPacketType.PRELOGIN.getCode(), preLoginPayload, 1)
                        .writeTo(client.getOutputStream());
                TdsLogicalMessage serverPre = TdsLogicalMessage.read(client.getInputStream());
                assertThat(serverPre.type()).isEqualTo(TdsPacketType.PRELOGIN.getCode());

                TdsLogicalMessage login7 = TdsLogicalMessage.singlePacket(
                        TdsPacketType.TDS7_LOGIN.getCode(), login7Payload, 1);
                login7.writeTo(client.getOutputStream());

                SqlServerDeferredIdentityRouter.Result result = routeFuture.get(5, TimeUnit.SECONDS);
                assertThat(result.rebound()).isFalse();
                assertThat(result.context()).isEqualTo(RoutingContext.of("fallback_user", "master"));
                assertThat(result.clientToBackendPrefix()).isEqualTo(login7.wireBytes());
                result.backendSocket().close();
            }
            backend.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void mismatchPathSilentPreLoginRebindsToMatchedBackend() throws Exception {
        byte[] preLoginPayload = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_OFF);
        byte[] login7Payload = Login7ObservationTest.buildLogin7Payload("routed_user", "tenant_b", "app", "host");

        try (ServerSocket fallbackServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket routedServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            ExecutorService pool = Executors.newFixedThreadPool(3);
            AtomicReference<byte[]> login7OnRouted = new AtomicReference<>();

            Future<?> fallbackBackend = pool.submit(() -> {
                try (Socket s = fallbackServer.accept()) {
                    TdsLogicalMessage.read(s.getInputStream());
                    TdsLogicalMessage.singlePacket(
                                    TdsPacketType.PRELOGIN.getCode(), preLoginPayload, 1)
                            .writeTo(s.getOutputStream());
                    try {
                        s.getInputStream().read();
                    } catch (IOException ignored) {
                    }
                }
                return null;
            });

            Future<?> routedBackend = pool.submit(() -> {
                try (Socket s = routedServer.accept()) {
                    TdsLogicalMessage pre = TdsLogicalMessage.read(s.getInputStream());
                    assertThat(pre.type()).isEqualTo(TdsPacketType.PRELOGIN.getCode());
                    TdsLogicalMessage.singlePacket(
                                    TdsPacketType.PRELOGIN.getCode(), preLoginPayload, 1)
                            .writeTo(s.getOutputStream());
                    TdsLogicalMessage login7 = TdsLogicalMessage.read(s.getInputStream());
                    login7OnRouted.set(login7.payload());
                    Thread.sleep(200);
                }
                return null;
            });

            BackendProvider fallback = new DirectBackendProvider(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    fallbackServer.getLocalPort(), 2000);
            BackendProvider routed = new DirectBackendProvider(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    routedServer.getLocalPort(), 2000);
            RoutingRule rule = new RoutingRule(null, "routed_user",
                    List.of(new WeightedEndpoint(new BackendEndpoint(
                            InetAddress.getLoopbackAddress().getHostAddress(),
                            routedServer.getLocalPort()))));
            RoutingBackendProvider routing = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule, routed)), fallback);

            Socket bootstrap = routing.acquire(RoutingContext.empty());
            Future<SqlServerDeferredIdentityRouter.Result> routeFuture = pool.submit(() ->
                    SqlServerDeferredIdentityRouter.route(
                            clientPair.serverSide, bootstrap, routing, Socket::close));

            try (Socket client = clientPair.clientSide) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                TdsLogicalMessage.singlePacket(
                                TdsPacketType.PRELOGIN.getCode(), preLoginPayload, 1)
                        .writeTo(out);
                TdsLogicalMessage.read(in);
                TdsLogicalMessage.singlePacket(
                                TdsPacketType.TDS7_LOGIN.getCode(), login7Payload, 1)
                        .writeTo(out);

                SqlServerDeferredIdentityRouter.Result result = routeFuture.get(5, TimeUnit.SECONDS);
                assertThat(result.rebound()).isTrue();
                assertThat(result.clientToBackendPrefix()).isEmpty();
                assertThat(result.context()).isEqualTo(RoutingContext.of("routed_user", "tenant_b"));
                assertThat(result.backendSocket().getPort()).isEqualTo(routedServer.getLocalPort());
                assertThat(login7OnRouted.get()).isEqualTo(login7Payload);
                result.backendSocket().close();
            }

            fallbackBackend.get(5, TimeUnit.SECONDS);
            routedBackend.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void encryptionPreLoginStaysOnBootstrapWithoutIdentity() throws Exception {
        byte[] clientPre = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_ON);
        byte[] serverPre = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_ON);

        try (ServerSocket bootstrapServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            ExecutorService pool = Executors.newFixedThreadPool(2);
            pool.submit(() -> {
                try (Socket s = bootstrapServer.accept()) {
                    TdsLogicalMessage.read(s.getInputStream());
                    TdsLogicalMessage.singlePacket(
                                    TdsPacketType.PRELOGIN.getCode(), serverPre, 1)
                            .writeTo(s.getOutputStream());
                    Thread.sleep(400);
                }
                return null;
            });

            RoutingRule rule = new RoutingRule(null, "anyone",
                    List.of(new WeightedEndpoint(new BackendEndpoint("127.0.0.1", 1))));
            BackendProvider fallback = new DirectBackendProvider(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    bootstrapServer.getLocalPort(), 2000);
            RoutingBackendProvider routing = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule, fallback)), fallback);

            Socket bootstrap = routing.acquire(RoutingContext.empty());
            Future<SqlServerDeferredIdentityRouter.Result> routeFuture = pool.submit(() ->
                    SqlServerDeferredIdentityRouter.route(
                            clientPair.serverSide, bootstrap, routing, Socket::close));

            try (Socket client = clientPair.clientSide) {
                TdsLogicalMessage.singlePacket(
                                TdsPacketType.PRELOGIN.getCode(), clientPre, 1)
                        .writeTo(client.getOutputStream());
                TdsLogicalMessage.read(client.getInputStream());

                SqlServerDeferredIdentityRouter.Result result = routeFuture.get(5, TimeUnit.SECONDS);
                assertThat(result.rebound()).isFalse();
                assertThat(result.context().isEmpty()).isTrue();
                assertThat(result.clientToBackendPrefix()).isEmpty();
                result.backendSocket().close();
            }
            pool.shutdownNow();
        }
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
