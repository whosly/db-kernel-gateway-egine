package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.postgresql.PostgreSQLCancelKeyRegistry;
import com.whosly.gateway.adapter.postgresql.PostgreSQLDatabaseEventExtractor;
import com.whosly.gateway.adapter.postgresql.PostgreSQLResultSetMaskingInterceptor;
import com.whosly.gateway.adapter.postgresql.PostgreSQLFrameCodec;
import com.whosly.gateway.adapter.postgresql.PostgreSQLProtocolErrorMapper;
import com.whosly.gateway.adapter.postgresql.PostgreSQLSession;
import com.whosly.gateway.adapter.postgresql.PostgreSQLStartupRouting;
import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.ProbedHandshake;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/**
 * PostgreSQL transparent protocol proxy adapter.
 *
 * <p>Accepts a client connection, peeks the first startup-family message to fill
 * a {@link com.whosly.gateway.adapter.protocol.RoutingContext}, opens the target
 * with that context, then relays bytes in both directions while a per-connection
 * {@link PostgreSQLSession} records observed protocol state. When the target
 * cannot be reached the client gets a PostgreSQL-native {@code ErrorResponse}
 * instead of a bare TCP reset.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "PostgreSQL";
    private static final int DEFAULT_PORT = 5432;
    private static final int FIRST_CLIENT_MESSAGE_TIMEOUT_MS = 5000;
    private static final PostgreSQLFrameCodec FRAME_CODEC = new PostgreSQLFrameCodec();
    private static final PostgreSQLProtocolErrorMapper GATEWAY_ERROR_MAPPER = new PostgreSQLProtocolErrorMapper();
    private static final ProtocolErrorResponder GATEWAY_ERROR_RESPONDER =
            new ProtocolErrorResponder(FRAME_CODEC, GATEWAY_ERROR_MAPPER);
    private static final PostgreSQLStartupRouting STARTUP_ROUTING =
            new PostgreSQLStartupRouting(FIRST_CLIENT_MESSAGE_TIMEOUT_MS);
    /**
     * Correlates CancelRequest keys with the sessions of this adapter, so an
     * observed cancel request can be attributed to the session it targets.
     */
    private final PostgreSQLCancelKeyRegistry cancelKeyRegistry = new PostgreSQLCancelKeyRegistry();

    public PostgreSQLProtocolAdapter() {
        super(PROTOCOL_NAME, DEFAULT_PORT);
    }

    @Override
    protected SqlParser createSqlParser() {
        return new DruidSqlParser();
    }

    @Override
    protected ProbedHandshake probeClientForRouting(Socket clientSocket) throws IOException {
        return STARTUP_ROUTING.probe(clientSocket);
    }

    @Override
    protected void handleClientConnection(Socket clientSocket) {
        String sessionId = "postgresql-" + UUID.randomUUID();
        PostgreSQLSession session = new PostgreSQLSession(sessionId);
        registerSession(session, clientSocket);

        BackendProvider backendProvider = backendProvider();
        ProbedHandshake probed;
        try {
            probed = probeClientForRouting(clientSocket);
        } catch (IOException e) {
            log.warn("PostgreSQL proxy session {} failed early client peek: {}", sessionId, e.getMessage());
            sendStartupError(clientSocket, e, ProbedHandshake.empty());
            session.close();
            cancelKeyRegistry.unregister(sessionId);
            unregisterSession(session);
            closeQuietly(clientSocket);
            return;
        }

        Socket targetSocket;
        try {
            targetSocket = backendProvider.acquire(probed.context());
        } catch (IOException e) {
            log.warn("PostgreSQL proxy session {} could not reach target {}:{}: {}",
                    sessionId, targetHost, targetPort, e.getMessage());
            sendStartupError(clientSocket, e, probed);
            session.close();
            cancelKeyRegistry.unregister(sessionId);
            unregisterSession(session);
            closeQuietly(clientSocket);
            return;
        }

        try {
            log.info("PostgreSQL proxy session {} connected {} to target {}:{} ({})",
                    sessionId, clientSocket.getRemoteSocketAddress(), targetHost, targetPort, probed.context());
            PostgreSQLDatabaseEventExtractor extractor = new PostgreSQLDatabaseEventExtractor(
                    PROTOCOL_NAME, sessionId, false, session, cancelKeyRegistry);
            DatabaseTrafficInspector trafficInspector = new DatabaseTrafficInspector(
                    extractor::inspect,
                    databaseTrafficObserver,
                    databaseRiskPolicy,
                    session,
                    new StatementClassifier(sqlParser),
                    extractor::isOpaqueTunnel,
                    isRequireCleartextInspection(),
                    getRuntimeMetrics());
            /*
             * Result-set masking shares the extractor with the observer, exactly as on the
             * MySQL side: the RowDescription is already tracked there, and a second state
             * machine would drift from it (rule 2.10). With no rule registered the engine
             * is inactive and the pipeline is unchanged.
             */
            MessagePipeline pipeline = maskingEngine.isActive()
                    ? MessagePipeline.of(trafficInspector,
                            new PostgreSQLResultSetMaskingInterceptor(extractor, maskingEngine))
                    : MessagePipeline.of(trafficInspector);
            new DuplexRelay(sessionId, pipeline, GATEWAY_ERROR_RESPONDER, rewriteLimits)
                    .relay(clientSocket, targetSocket, probed.replayToBackend());
        } catch (IOException e) {
            log.warn("PostgreSQL proxy session {} closed: {}", sessionId, e.getMessage());
        } finally {
            session.close();
            // Drop the cancel keys with the session so a later cancel cannot
            // resolve to a connection that no longer exists.
            cancelKeyRegistry.unregister(sessionId);
            // Let the audit sink release the sequence counter of this session.
            databaseTrafficObserver.onSessionClosed(sessionId);
            unregisterSession(session);
            releaseBackend(backendProvider, targetSocket, session);
            closeQuietly(clientSocket);
        }
    }

    /**
     * Reports a gateway failure with a PostgreSQL-native message.
     *
     * <p>PostgreSQL answers {@code SSLRequest}/{@code GSSENCRequest} with a
     * single byte before the cleartext {@code StartupMessage} arrives, so the
     * first client message must be inspected before deciding the response:</p>
     * <ul>
     *   <li>{@code CancelRequest}: send nothing, the connection is finished.</li>
     *   <li>{@code SSLRequest}/{@code GSSENCRequest}: reply {@code N} (reject
     *       encryption), read the following {@code StartupMessage}, then report
     *       the error.</li>
     *   <li>{@code StartupMessage}: report the error immediately.</li>
     * </ul>
     */
    @Override
    protected void rejectClientConnection(Socket clientSocket) {
        log.warn("Rejecting PostgreSQL client connection from {}", clientSocket.getRemoteSocketAddress());
        sendStartupError(clientSocket, new GatewayException(GatewayErrorMapping.RESOURCE_EXHAUSTED),
                ProbedHandshake.empty());
        closeQuietly(clientSocket);
    }

    private static void sendStartupError(Socket clientSocket, Throwable cause, ProbedHandshake probed) {
        try {
            clientSocket.setSoTimeout(FIRST_CLIENT_MESSAGE_TIMEOUT_MS);
            ProtocolMessage first;
            if (probed.hasReplay()) {
                first = FRAME_CODEC.readStartupMessage(new ByteArrayInputStream(probed.replayToBackend()));
            } else {
                first = FRAME_CODEC.readStartupMessage(clientSocket.getInputStream());
            }
            int requestCode = PostgreSQLStartupRouting.requestCode(first);

            if (requestCode == PostgreSQLFrameCodec.CANCEL_REQUEST_CODE) {
                return;
            }
            if (requestCode == PostgreSQLFrameCodec.SSL_REQUEST_CODE
                    || requestCode == PostgreSQLFrameCodec.GSSENC_REQUEST_CODE) {
                clientSocket.getOutputStream().write('N');
                clientSocket.getOutputStream().flush();
                first = FRAME_CODEC.readStartupMessage(clientSocket.getInputStream());
                requestCode = PostgreSQLStartupRouting.requestCode(first);
            }
            if (requestCode == PostgreSQLFrameCodec.CANCEL_REQUEST_CODE) {
                return;
            }

            GATEWAY_ERROR_RESPONDER.respond(clientSocket.getOutputStream(), cause);
        } catch (IOException | RuntimeException e) {
            log.debug("PostgreSQL proxy session failed to send gateway error: {}", e.getMessage());
        }
    }

    /**
     * Index of observed backend cancel keys, keyed by backend process id and
     * secret. Exposed for inspection; key material is never logged.
     */
    public PostgreSQLCancelKeyRegistry getCancelKeyRegistry() {
        return cancelKeyRegistry;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
