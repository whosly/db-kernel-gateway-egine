package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficInspector;
import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.adapter.protocol.MessagePipeline;
import com.whosly.gateway.adapter.protocol.ProbedHandshake;
import com.whosly.gateway.adapter.sqlserver.SqlServerDatabaseEventExtractor;
import com.whosly.gateway.adapter.sqlserver.SqlServerSession;
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
 * <p><b>Maturity (honest):</b> P0 transparent {@link DuplexRelay} plus P1-lite
 * cleartext observation of Login7 identity and SQL_BATCH text when the stream is
 * not encrypted. This is <em>not</em> MySQL/PostgreSQL parity — no result-set
 * masking, no Attention/cancel, no deep token decode, no protocol session reset,
 * no Login7-driven routing. See {@code docs/SQLSERVER_TDS_PLAN.md}.</p>
 *
 * <p><b>Handshake:</b> TDS is client-first (PreLogin → Login7).
 * {@link BackendProvider#acquire} still uses an empty
 * {@link com.whosly.gateway.adapter.protocol.RoutingContext}; Login7 is observed
 * on the duplex path after acquire (session labels / traffic events only; bytes
 * forwarded unchanged).</p>
 */
public class SqlServerProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(SqlServerProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "SQLServer";
    /** Default gateway listen port (clients connect here; target stays 1433). */
    private static final int DEFAULT_PORT = 31433;

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
            // No TDS result-set masking yet (deferred). Observation + risk only.
            MessagePipeline pipeline = MessagePipeline.of(trafficInspector);
            // No TDS-native error responder yet — policy deny closes the socket.
            new DuplexRelay(sessionId, pipeline, null, rewriteLimits)
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
        // No forged TDS ERROR token yet — close quietly (fail-closed for limit).
        closeQuietly(clientSocket);
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
