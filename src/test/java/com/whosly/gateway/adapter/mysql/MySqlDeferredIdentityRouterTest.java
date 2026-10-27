package com.whosly.gateway.adapter.mysql;

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

class MySqlDeferredIdentityRouterTest {

    private static final MySQLFrameCodec CODEC = new MySQLFrameCodec();

    @Test
    void matchPathReplaysHandshakeResponseOnBootstrap() throws Exception {
        byte[] scramble = scramble(1);
        byte[] greetingPayload = MySqlWireFixtures.initialHandshake("8.0.36", 7L, scramble, "mysql_native_password");
        long caps = MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()
                | MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()
                | MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag();
        byte[] auth = scramble.clone();
        byte[] hrPayload = MySqlWireFixtures.handshakeResponsePayload(
                caps, "fallback_user", auth, "app", "mysql_native_password");

        try (ServerSocket bootstrapServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<?> backend = pool.submit(() -> {
                try (Socket backendSock = bootstrapServer.accept()) {
                    CODEC.write(
                            com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(greetingPayload, 0),
                            backendSock.getOutputStream());
                    backendSock.getOutputStream().flush();
                    // Match path: router does not read more from bootstrap until DuplexRelay;
                    // keep socket open briefly.
                    Thread.sleep(500);
                }
                return null;
            });

            BackendProvider fallback = new DirectBackendProvider(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    bootstrapServer.getLocalPort(),
                    2000);
            RoutingBackendProvider routing = new RoutingBackendProvider(List.of(), fallback);

            Socket bootstrap = routing.acquire(RoutingContext.empty());
            Future<MySqlDeferredIdentityRouter.Result> routeFuture = pool.submit(() ->
                    MySqlDeferredIdentityRouter.route(
                            clientPair.serverSide, bootstrap, routing, Socket::close));

            // Client sees greeting then sends HR
            try (Socket client = clientPair.clientSide) {
                var greeting = CODEC.read(client.getInputStream());
                assertThat(MySqlInitialHandshake.tryParse(greeting.payload())).isPresent();
                CODEC.write(
                        com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(hrPayload, 1),
                        client.getOutputStream());
                client.getOutputStream().flush();

                MySqlDeferredIdentityRouter.Result result = routeFuture.get(5, TimeUnit.SECONDS);
                assertThat(result.rebound()).isFalse();
                assertThat(result.context()).isEqualTo(RoutingContext.of("fallback_user", "app"));
                assertThat(result.clientToBackendPrefix()).isEqualTo(MySqlWireFixtures.packet(1, hrPayload));
                result.backendSocket().close();
            }
            backend.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void mismatchPathAuthSwitchRebindsToMatchedBackend() throws Exception {
        byte[] scrambleA = scramble(2);
        byte[] scrambleB = scramble(9);
        byte[] greetingA = MySqlWireFixtures.initialHandshake("8.0.36", 1L, scrambleA, "mysql_native_password");
        byte[] greetingB = MySqlWireFixtures.initialHandshake("8.0.36", 2L, scrambleB, "mysql_native_password");
        long caps = MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                | MySQLCapability.CLIENT_SECURE_CONNECTION.getFlag()
                | MySQLCapability.CLIENT_CONNECT_WITH_DB.getFlag()
                | MySQLCapability.CLIENT_PLUGIN_AUTH.getFlag();
        byte[] hrPayload = MySqlWireFixtures.handshakeResponsePayload(
                caps, "routed_user", scrambleA.clone(), "tenant_b", "mysql_native_password");
        byte[] switchResponseAuth = scrambleB.clone();

        try (ServerSocket fallbackServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket routedServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {

            ExecutorService pool = Executors.newFixedThreadPool(3);
            AtomicReference<byte[]> rebuiltHrSeen = new AtomicReference<>();

            Future<?> fallbackBackend = pool.submit(() -> {
                try (Socket s = fallbackServer.accept()) {
                    CODEC.write(
                            com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(greetingA, 0),
                            s.getOutputStream());
                    s.getOutputStream().flush();
                    // Bootstrap is released on rebind — accept EOF
                    try {
                        s.getInputStream().read();
                    } catch (IOException ignored) {
                    }
                }
                return null;
            });

            Future<?> routedBackend = pool.submit(() -> {
                try (Socket s = routedServer.accept()) {
                    CODEC.write(
                            com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(greetingB, 0),
                            s.getOutputStream());
                    s.getOutputStream().flush();
                    var hr = CODEC.read(s.getInputStream());
                    rebuiltHrSeen.set(hr.payload());
                    CODEC.write(
                            com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(
                                    MySqlWireFixtures.okPayload(), 2),
                            s.getOutputStream());
                    s.getOutputStream().flush();
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
            Future<MySqlDeferredIdentityRouter.Result> routeFuture = pool.submit(() ->
                    MySqlDeferredIdentityRouter.route(
                            clientPair.serverSide, bootstrap, routing, Socket::close));

            try (Socket client = clientPair.clientSide) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                CODEC.read(in); // greeting A
                CODEC.write(
                        com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(hrPayload, 1), out);
                out.flush();

                var authSwitch = CODEC.read(in);
                assertThat(authSwitch.payload()[0] & 0xFF).isEqualTo(MySqlAuthSwitch.AUTH_SWITCH_HEADER);
                assertThat(authSwitch.sequence()).hasValue(2);

                CODEC.write(
                        com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(switchResponseAuth, 3),
                        out);
                out.flush();

                var ok = CODEC.read(in);
                assertThat(ok.payload()[0] & 0xFF).isEqualTo(0x00);

                MySqlDeferredIdentityRouter.Result result = routeFuture.get(5, TimeUnit.SECONDS);
                assertThat(result.rebound()).isTrue();
                assertThat(result.clientToBackendPrefix()).isEmpty();
                assertThat(result.context()).isEqualTo(RoutingContext.of("routed_user", "tenant_b"));
                assertThat(result.backendSocket().getPort()).isEqualTo(routedServer.getLocalPort());

                assertThat(rebuiltHrSeen.get()).isNotNull();
                var rebuilt = MySqlHandshakeResponseCodec.tryParse(rebuiltHrSeen.get());
                assertThat(rebuilt).isPresent();
                assertThat(rebuilt.get().username()).isEqualTo("routed_user");
                assertThat(rebuilt.get().database()).contains("tenant_b");
                assertThat(rebuilt.get().authResponse()).isEqualTo(switchResponseAuth);

                result.backendSocket().close();
            }

            fallbackBackend.get(5, TimeUnit.SECONDS);
            routedBackend.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void sslRequestStaysOnBootstrapWithoutIdentity() throws Exception {
        byte[] scramble = scramble(3);
        byte[] greetingPayload = MySqlWireFixtures.initialHandshake(
                "8.0.36", 3L, scramble, "mysql_native_password");
        long caps = MySQLCapability.CLIENT_PROTOCOL_41.getFlag()
                | MySQLCapability.CLIENT_SSL.getFlag();
        byte[] sslRequest = new byte[32];
        sslRequest[0] = (byte) (caps & 0xFF);
        sslRequest[1] = (byte) ((caps >> 8) & 0xFF);
        sslRequest[2] = (byte) ((caps >> 16) & 0xFF);
        sslRequest[3] = (byte) ((caps >> 24) & 0xFF);

        try (ServerSocket bootstrapServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocketPair clientPair = SocketPair.open()) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            pool.submit(() -> {
                try (Socket s = bootstrapServer.accept()) {
                    CODEC.write(
                            com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(greetingPayload, 0),
                            s.getOutputStream());
                    s.getOutputStream().flush();
                    Thread.sleep(400);
                }
                return null;
            });

            RoutingRule rule = new RoutingRule(null, "anyone",
                    List.of(new WeightedEndpoint(new BackendEndpoint("127.0.0.1", 1))));
            // routed provider unused — SSL path must not acquire it
            BackendProvider fallback = new DirectBackendProvider(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    bootstrapServer.getLocalPort(), 2000);
            RoutingBackendProvider routing = new RoutingBackendProvider(
                    List.of(RoutingBackendProvider.bind(rule, fallback)), fallback);

            Socket bootstrap = routing.acquire(RoutingContext.empty());
            Future<MySqlDeferredIdentityRouter.Result> routeFuture = pool.submit(() ->
                    MySqlDeferredIdentityRouter.route(
                            clientPair.serverSide, bootstrap, routing, Socket::close));

            try (Socket client = clientPair.clientSide) {
                CODEC.read(client.getInputStream());
                CODEC.write(
                        com.whosly.gateway.adapter.protocol.ProtocolMessage.untyped(sslRequest, 1),
                        client.getOutputStream());
                client.getOutputStream().flush();
                MySqlDeferredIdentityRouter.Result result = routeFuture.get(5, TimeUnit.SECONDS);
                assertThat(result.rebound()).isFalse();
                assertThat(result.context().isEmpty()).isTrue();
                assertThat(result.clientToBackendPrefix()).isEqualTo(MySqlWireFixtures.packet(1, sslRequest));
                result.backendSocket().close();
            }
            pool.shutdownNow();
        }
    }

    private static byte[] scramble(int seed) {
        byte[] s = new byte[20];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (seed + i);
        }
        return s;
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
