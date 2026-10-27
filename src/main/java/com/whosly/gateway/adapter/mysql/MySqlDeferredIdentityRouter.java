package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.GatewayException;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import com.whosly.gateway.adapter.protocol.RoutingBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;

/**
 * MySQL first-connect identity routing via deferred AuthSwitch rebind.
 *
 * <p>Bootstrap always uses {@code acquire(empty)} (server-first). After the real
 * backend greeting is forwarded and the client Handshake Response is parsed:</p>
 * <ul>
 *   <li>If {@link RoutingBackendProvider#selectsFallback(RoutingContext)} —
 *       replay the Handshake Response; path stays fully transparent.</li>
 *   <li>Else — close bootstrap, {@code acquire(context)} on the matched provider,
 *       AuthSwitch the client onto that backend's scramble, rebuild Handshake
 *       Response, finish auth with sequence fixup, then return for transparent
 *       duplex.</li>
 * </ul>
 *
 * <p>Client SSLRequest (TLS before full Handshake Response) aborts deferred
 * routing and stays on the bootstrap backend.</p>
 *
 */
public final class MySqlDeferredIdentityRouter {

    private static final Logger log = LoggerFactory.getLogger(MySqlDeferredIdentityRouter.class);
    private static final MySQLFrameCodec CODEC = new MySQLFrameCodec();
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    private MySqlDeferredIdentityRouter() {
    }

    /**
     * Result after the deferred handshake phase. {@link #clientToBackendPrefix()}
     * is replayed on client→backend when non-empty (match path). On the rebind
     * path the Handshake Response was already written to the backend and the
     * prefix is empty.
     */
    public record Result(Socket backendSocket, RoutingContext context, byte[] clientToBackendPrefix,
                         boolean rebound) {
        public Result {
            Objects.requireNonNull(backendSocket, "backendSocket");
            Objects.requireNonNull(context, "context");
            clientToBackendPrefix = clientToBackendPrefix == null ? new byte[0] : clientToBackendPrefix;
        }
    }

    /**
     * @param client            accepted client socket
     * @param bootstrapBackend  socket from {@code acquire(empty)}
     * @param backendProvider   must be {@link RoutingBackendProvider} for rebind
     * @param releaseBootstrap  closes/releases the bootstrap socket on rebind
     */
    public static Result route(Socket client, Socket bootstrapBackend, BackendProvider backendProvider,
                               BootstrapReleaser releaseBootstrap) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(bootstrapBackend, "bootstrapBackend");
        Objects.requireNonNull(backendProvider, "backendProvider");
        Objects.requireNonNull(releaseBootstrap, "releaseBootstrap");

        int prevClientTimeout = client.getSoTimeout();
        int prevBackendTimeout = bootstrapBackend.getSoTimeout();
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            bootstrapBackend.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            ProtocolMessage greeting = CODEC.read(bootstrapBackend.getInputStream());
            CODEC.write(greeting, client.getOutputStream());
            client.getOutputStream().flush();

            Optional<MySqlInitialHandshake> bootstrapHandshake =
                    MySqlInitialHandshake.tryParse(greeting.payload());
            if (bootstrapHandshake.isEmpty()) {
                log.debug("MySQL deferred routing: unparseable greeting — transparent on bootstrap");
                return transparentRemainder(client, bootstrapBackend, RoutingContext.empty());
            }

            ProtocolMessage clientFirst = CODEC.read(client.getInputStream());
            byte[] hrPacket = CODEC.packet(clientFirst.payload(), clientFirst.sequence().orElse(1));
            Optional<MySqlHandshakeResponseCodec.Parsed> parsed =
                    MySqlHandshakeResponseCodec.tryParse(clientFirst.payload());
            if (parsed.isEmpty()) {
                log.debug("MySQL deferred routing: unparseable Handshake Response — transparent");
                return new Result(bootstrapBackend, RoutingContext.empty(), hrPacket, false);
            }
            if (parsed.get().requestsSslOnly()) {
                log.info("MySQL deferred routing: SSLRequest observed — stay on bootstrap (no identity yet)");
                return new Result(bootstrapBackend, RoutingContext.empty(), hrPacket, false);
            }

            RoutingContext identity = toRoutingContext(parsed.get());
            if (!(backendProvider instanceof RoutingBackendProvider routing) || routing.selectsFallback(identity)) {
                log.debug("MySQL deferred routing: identity {} uses bootstrap/fallback", identity);
                return new Result(bootstrapBackend, identity, hrPacket, false);
            }

            log.info("MySQL deferred routing: identity {} mismatches bootstrap — AuthSwitch rebind", identity);
            releaseBootstrap.release(bootstrapBackend);
            Socket selected;
            try {
                selected = routing.acquire(identity);
            } catch (IOException e) {
                throw new GatewayException(GatewayErrorMapping.TARGET_UNAVAILABLE, e);
            }
            try {
                selected.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                rebindViaAuthSwitch(client, selected, parsed.get(), identity,
                        clientFirst.sequence().orElse(1));
                return new Result(selected, identity, new byte[0], true);
            } catch (IOException | RuntimeException e) {
                closeQuietly(selected);
                throw e;
            }
        } catch (SocketTimeoutException e) {
            throw new GatewayException(GatewayErrorMapping.PROTOCOL_VIOLATION, e);
        } finally {
            restoreTimeout(client, prevClientTimeout);
            // bootstrap may already be released; ignore failures
            try {
                if (!bootstrapBackend.isClosed()) {
                    restoreTimeout(bootstrapBackend, prevBackendTimeout);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface BootstrapReleaser {
        void release(Socket bootstrap) throws IOException;
    }

    private static Result transparentRemainder(Socket client, Socket bootstrap, RoutingContext context)
            throws IOException {
        // Greeting already forwarded; nothing buffered from client — plain duplex.
        return new Result(bootstrap, context, new byte[0], false);
    }

    private static void rebindViaAuthSwitch(Socket client, Socket selected,
                                            MySqlHandshakeResponseCodec.Parsed originalHr,
                                            RoutingContext identity,
                                            int handshakeResponseSequence) throws IOException {
        ProtocolMessage newGreeting = CODEC.read(selected.getInputStream());
        MySqlInitialHandshake handshake = MySqlInitialHandshake.tryParse(newGreeting.payload())
                .orElseThrow(() -> new GatewayException(GatewayErrorMapping.TARGET_HANDSHAKE_FAILED));

        int authSwitchSeq = (handshakeResponseSequence + 1) & 0xFF;
        byte[] switchPayload = MySqlAuthSwitch.requestPayload(
                handshake.authPluginName(), handshake.scramble());
        CODEC.write(ProtocolMessage.untyped(switchPayload, authSwitchSeq), client.getOutputStream());
        client.getOutputStream().flush();

        ProtocolMessage authSwitchResponse = CODEC.read(client.getInputStream());
        byte[] newAuth = authSwitchResponse.payload();
        byte[] rebuiltHr = MySqlHandshakeResponseCodec.rebuild(
                originalHr, newAuth, handshake.authPluginName());
        CODEC.write(ProtocolMessage.untyped(rebuiltHr, 1), selected.getOutputStream());
        selected.getOutputStream().flush();

        completeAuthWithSequenceFixup(client, selected,
                (authSwitchResponse.sequence().orElse(authSwitchSeq) + 1) & 0xFF,
                2);
        log.info("MySQL deferred routing: rebound to selected backend for {}", identity);
    }

    /**
     * Forwards auth completion packets with sequence translation until OK/ERR.
     * After OK, command-phase sequences restart at 0 on both sides.
     */
    static void completeAuthWithSequenceFixup(Socket client, Socket backend,
                                              int nextClientSeq, int nextBackendSeq) throws IOException {
        InputStream backendIn = backend.getInputStream();
        OutputStream clientOut = client.getOutputStream();
        InputStream clientIn = client.getInputStream();
        OutputStream backendOut = backend.getOutputStream();

        int clientSeq = nextClientSeq;
        int backendSeq = nextBackendSeq;
        for (int guard = 0; guard < 16; guard++) {
            ProtocolMessage fromBackend = CODEC.read(backendIn);
            byte[] payload = fromBackend.payload();
            CODEC.write(ProtocolMessage.untyped(payload, clientSeq), clientOut);
            clientOut.flush();
            clientSeq = (clientSeq + 1) & 0xFF;

            if (payload.length == 0) {
                continue;
            }
            int header = payload[0] & 0xFF;
            if (header == 0x00 || header == 0xFF) {
                return; // OK or ERR — auth done
            }
            if (header == 0x01 && payload.length >= 2 && (payload[1] & 0xFF) == 0x03) {
                // caching_sha2 fast-auth success — server continues with OK, no client reply
                continue;
            }
            // AuthMoreData / AuthSwitch / other challenge — client must reply
            ProtocolMessage fromClient = CODEC.read(clientIn);
            CODEC.write(ProtocolMessage.untyped(fromClient.payload(), backendSeq), backendOut);
            backendOut.flush();
            backendSeq = (backendSeq + 1) & 0xFF;
        }
        throw new GatewayException(GatewayErrorMapping.TARGET_HANDSHAKE_FAILED);
    }

    private static RoutingContext toRoutingContext(MySqlHandshakeResponseCodec.Parsed parsed) {
        if (parsed.database().isPresent()) {
            return RoutingContext.of(parsed.username(), parsed.database().get());
        }
        if (parsed.username() == null || parsed.username().isEmpty()) {
            return RoutingContext.empty();
        }
        return RoutingContext.ofUsername(parsed.username());
    }

    private static void restoreTimeout(Socket socket, int timeout) {
        try {
            socket.setSoTimeout(timeout);
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
