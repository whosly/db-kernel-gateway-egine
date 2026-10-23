package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficInspector;
import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.adapter.protocol.GatewayErrorMapping;
import com.whosly.gateway.adapter.protocol.GatewayException;
import com.whosly.gateway.adapter.protocol.MessagePipeline;
import com.whosly.gateway.adapter.protocol.ProbedHandshake;
import com.whosly.gateway.adapter.protocol.ProtocolErrorResponder;
import com.whosly.gateway.adapter.sqlserver.SqlServerDatabaseEventExtractor;
import com.whosly.gateway.adapter.sqlserver.SqlServerResultSetMaskingInterceptor;
import com.whosly.gateway.adapter.sqlserver.SqlServerGatewayErrorMapper;
import com.whosly.gateway.adapter.sqlserver.SqlServerSession;
import com.whosly.gateway.adapter.sqlserver.TdsFrameCodec;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import com.whosly.gateway.parser.StatementClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/**
 * SQL Server (TDS) protocol proxy adapter.
 *
 * <p><b>Maturity (honest):</b> transparent {@link DuplexRelay} plus cleartext
 * observation (Login7, SQL_BATCH, RPC name, Bulk, ERROR, Attention/DONE_ATTN),
 * gateway-side ERROR+DONE rejects, optional result-set ROW/NBCROW masking
 * (text/guid/numeric/temporal/money/TEXTPTR/varbinary PLP; XML/UDT NULL-only;
 * TVP/unknown fail-closed), and {@code sp_reset_connection} pool reset.
 * Still <em>not</em> MySQL/PostgreSQL parity — no Login7-driven {@code acquire}
 * routing, no deep RPC parameter rewrite. See {@code docs/SQLSERVER_TDS_PLAN.md}.</p>
 *
 * <p><b>Handshake / routing:</b> TDS is client-first (PreLogin → Login7).
 * PreLogin carries no user/database, so {@link BackendProvider#acquire} uses an
 * empty {@link com.whosly.gateway.adapter.protocol.RoutingContext}. Login7 is
 * observed on the duplex path after acquire (session labels / traffic events /
 * {@code routing.hint} attribute only; bytes forwarded unchanged). True
 * Login7-before-acquire routing would require forging a PreLogin response and
 * is deferred.</p>
 *
 * <p><b>Cancel:</b> Attention (packet type {@code 0x06}) rides the same
 * connection as the query. The relay forwards it transparently; the extractor
 * emits {@code ATTENTION} / {@code ATTENTION_ACK} (DONE_ATTN) for audit —
 * observe-only, no synthesized Attention and no gateway-initiated backend
 * close (same philosophy as PostgreSQL CancelRequest associate-only).</p>
 */
public class SqlServerProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(SqlServerProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "SQLServer";
    /** Default gateway listen port (clients connect here; target stays 1433). */
    private static final int DEFAULT_PORT = 31433;
    private static final TdsFrameCodec FRAME_CODEC = new TdsFrameCodec();
    private static final SqlServerGatewayErrorMapper GATEWAY_ERROR_MAPPER = new SqlServerGatewayErrorMapper();
    private static final ProtocolErrorResponder GATEWAY_ERROR_RESPONDER =
            new ProtocolErrorResponder(FRAME_CODEC, GATEWAY_ERROR_MAPPER);

    public SqlServerProtocolAdapter() {
        super(PROTOCOL_NAME, DEFAULT_PORT);
        this.targetPort = 1433;
    }

    @Override
    protected SqlParser createSqlParser() {
        return new DruidSqlParser();
    }

    /**
     * Client-first, but PreLogin carries no user/database. Empty probe — Login7
     * observation runs after acquire and does not select the backend in this slice.
     */
    @Override
    protected ProbedHandshake probeClientForRouting(Socket clientSocket) {
        return ProbedHandshake.empty();
    }

    @Override
    protected void handleClientConnection(Socket clientSocket) {
        String sessionId = "sqlserver-" + UUID.randomUUID();
        SqlServerSession session = new SqlServerSession(sessionId);
        registerSession(session, clientSocket);

        BackendProvider backendProvider = backendProvider();
        ProbedHandshake probed = probeClientForRouting(clientSocket);
        Socket targetSocket;
        try {
            targetSocket = backendProvider.acquire(probed.context());
        } catch (IOException e) {
            log.warn("SQL Server proxy session {} could not reach target {}:{}: {}",
                    sessionId, targetHost, targetPort, e.getMessage());
            sendGatewayError(clientSocket, e);
            session.close();
            unregisterSession(session);
            closeQuietly(clientSocket);
            return;
        }

        try {
            log.info("SQL Server proxy session {} connected {} to target {}:{} (routing={})",
                    sessionId, clientSocket.getRemoteSocketAddress(), targetHost, targetPort, probed.context());
            SqlServerDatabaseEventExtractor extractor =
                    new SqlServerDatabaseEventExtractor(PROTOCOL_NAME, sessionId, session);
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
                            new SqlServerResultSetMaskingInterceptor(extractor, maskingEngine))
                    : MessagePipeline.of(trafficInspector);
            new DuplexRelay(sessionId, pipeline, GATEWAY_ERROR_RESPONDER, rewriteLimits)
                    .relay(clientSocket, targetSocket, probed.replayToBackend());
        } catch (IOException e) {
            log.warn("SQL Server proxy session {} closed: {}", sessionId, e.getMessage());
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
        log.warn("Rejecting SQL Server client connection from {}", clientSocket.getRemoteSocketAddress());
        sendGatewayError(clientSocket, new GatewayException(GatewayErrorMapping.RESOURCE_EXHAUSTED));
        closeQuietly(clientSocket);
    }

    private static void sendGatewayError(Socket clientSocket, Throwable cause) {
        try {
            GATEWAY_ERROR_RESPONDER.respond(clientSocket.getOutputStream(), cause);
        } catch (IOException e) {
            log.debug("SQL Server proxy session failed to send gateway error: {}", e.getMessage());
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
