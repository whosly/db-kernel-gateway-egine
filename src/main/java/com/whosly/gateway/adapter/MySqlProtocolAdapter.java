package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLDatabaseEventExtractor;
import com.whosly.gateway.adapter.mysql.MySQLFrameCodec;
import com.whosly.gateway.adapter.mysql.MySQLResultSetMaskingInterceptor;
import com.whosly.gateway.adapter.mysql.MySQLSession;
import com.whosly.gateway.adapter.mysql.MySqlDeferredIdentityRouter;
import com.whosly.gateway.adapter.mysql.MySqlGatewayErrorMapper;
import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.ProbedHandshake;
import com.whosly.gateway.adapter.protocol.RoutingBackendProvider;
import com.whosly.gateway.adapter.protocol.RoutingContext;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficInspector;
import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.GatewayException;
import com.whosly.gateway.adapter.protocol.MessagePipeline;
import com.whosly.gateway.adapter.protocol.ProtocolErrorResponder;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.StatementClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/**
 * MySQL transparent protocol proxy adapter.
 *
 * <p><b>Routing identity (P1-4):</b> MySQL is server-first — the backend must send
 * the Initial Handshake before the client Handshake Response carries username /
 * database. Forging a greeting is forbidden. When the backend provider is a
 * {@link RoutingBackendProvider} with rules, {@link MySqlDeferredIdentityRouter}
 * forwards the <em>real</em> bootstrap greeting, parses Handshake Response, and
 * either stays on fallback (transparent) or AuthSwitch-rebinds to the matched
 * backend. Without routing rules, behaviour is unchanged: {@code acquire(empty)}
 * then duplex.</p>
 */
public class MySqlProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MySqlProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "MySQL";
    private static final int DEFAULT_PORT = 3307;
    private static final MySqlGatewayErrorMapper GATEWAY_ERROR_MAPPER = new MySqlGatewayErrorMapper();
    private static final MySQLFrameCodec FRAME_CODEC = new MySQLFrameCodec();
    private static final ProtocolErrorResponder GATEWAY_ERROR_RESPONDER =
            new ProtocolErrorResponder(FRAME_CODEC, GATEWAY_ERROR_MAPPER);

    public MySqlProtocolAdapter() {
        super(PROTOCOL_NAME, DEFAULT_PORT);
    }

    @Override
    protected SqlParser createSqlParser() {
        return new DruidSqlParser();
    }

    /**
     * Server-first: no client bytes before backend greeting without forging.
     * Identity routing uses {@link MySqlDeferredIdentityRouter} after bootstrap
     * acquire instead of this probe.
     */
    @Override
    protected ProbedHandshake probeClientForRouting(Socket clientSocket) {
        return ProbedHandshake.empty();
    }

    @Override
    protected void handleClientConnection(Socket clientSocket) {
        String sessionId = "mysql-" + UUID.randomUUID();
        MySQLSession session = new MySQLSession(sessionId);
        registerSession(session, clientSocket);

        BackendProvider backendProvider = backendProvider();
        Socket targetSocket = null;
        byte[] clientPrefix = new byte[0];
        RoutingContext routed = RoutingContext.empty();
        boolean authAlreadyComplete = false;
        try {
            // Bootstrap always empty — server-first; deferred router may rebind.
            Socket bootstrap = backendProvider.acquire(RoutingContext.empty());
            targetSocket = bootstrap;
            if (backendProvider instanceof RoutingBackendProvider routing
                    && !routing.rules().isEmpty()) {
                MySqlDeferredIdentityRouter.Result deferred = MySqlDeferredIdentityRouter.route(
                        clientSocket,
                        bootstrap,
                        routing,
                        socket -> releaseBackend(backendProvider, socket, null));
                targetSocket = deferred.backendSocket();
                clientPrefix = deferred.clientToBackendPrefix();
                routed = deferred.context();
                authAlreadyComplete = deferred.rebound();
                applyRoutedIdentity(session, routed);
            }
        } catch (IOException | GatewayException e) {
            log.warn("MySQL proxy session {} could not complete backend route {}:{}: {}",
                    sessionId, targetHost, targetPort, e.getMessage());
            sendGatewayError(clientSocket, e);
            session.close();
            unregisterSession(session);
            closeQuietly(clientSocket);
            if (targetSocket != null) {
                releaseBackend(backendProvider, targetSocket, null);
            }
            return;
        }

        try {
            log.info("MySQL proxy session {} connected {} to target {}:{} (routing={}, rebound={})",
                    sessionId, clientSocket.getRemoteSocketAddress(), targetHost, targetPort,
                    routed, authAlreadyComplete);
            // After AuthSwitch rebind, handshake/auth already finished outside the relay.
            MySQLDatabaseEventExtractor extractor =
                    new MySQLDatabaseEventExtractor(PROTOCOL_NAME, sessionId, authAlreadyComplete, session);
            DatabaseTrafficInspector trafficInspector = new DatabaseTrafficInspector(
                    extractor::inspect,
                    databaseTrafficObserver,
                    databaseRiskPolicy,
                    session,
                    new StatementClassifier(sqlParser),
                    extractor::isOpaqueTunnel,
                    isRequireCleartextInspection(),
                    getRuntimeMetrics());
            MessagePipeline pipeline = maskingEngine.isActive()
                    ? MessagePipeline.of(trafficInspector,
                            new MySQLResultSetMaskingInterceptor(extractor, maskingEngine))
                    : MessagePipeline.of(trafficInspector);
            new DuplexRelay(sessionId, pipeline, GATEWAY_ERROR_RESPONDER, rewriteLimits)
                    .relay(clientSocket, targetSocket, clientPrefix);
        } catch (IOException e) {
            log.warn("MySQL proxy session {} closed: {}", sessionId, e.getMessage());
        } finally {
            session.close();
            databaseTrafficObserver.onSessionClosed(sessionId);
            unregisterSession(session);
            releaseBackend(backendProvider, targetSocket, session);
            closeQuietly(clientSocket);
        }
    }

    @Override
    protected void rejectClientConnection(Socket clientSocket) {
        log.warn("Rejecting MySQL client connection from {}", clientSocket.getRemoteSocketAddress());
        sendGatewayError(clientSocket, new GatewayException(GatewayErrorMapping.RESOURCE_EXHAUSTED));
        closeQuietly(clientSocket);
    }


    private static void applyRoutedIdentity(MySQLSession session, RoutingContext routed) {
        if (routed == null || routed.isEmpty()) {
            return;
        }
        routed.username().ifPresent(user -> session.putAttribute("client.user", user));
        routed.database().ifPresent(db -> {
            session.putAttribute("client.database", db);
            session.setCurrentDatabase(db);
        });
    }

    private static void sendGatewayError(Socket clientSocket, Throwable cause) {
        try {
            GATEWAY_ERROR_RESPONDER.respond(clientSocket.getOutputStream(), cause);
        } catch (IOException e) {
            log.debug("MySQL proxy session failed to send gateway error: {}", e.getMessage());
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
