package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.BackendProvider;
import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.adapter.protocol.ProbedHandshake;
import com.whosly.gateway.adapter.sqlserver.SqlServerSession;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/**
 * SQL Server (TDS) transparent protocol proxy adapter (P0 scaffold).
 *
 * <p><b>Handshake shape:</b> TDS is client-first (PreLogin → Login7). P0 does
 * <em>not</em> peek Login7 for routing identity — {@link BackendProvider#acquire}
 * uses an empty {@link com.whosly.gateway.adapter.protocol.RoutingContext} and the
 * duplex path forwards PreLogin/Login7/tabular bytes verbatim. P1 may add
 * observation of login user/db when parseable; deeper tokens / masking / cancel
 * are deferred (see {@code docs/SQLSERVER_TDS_PLAN.md}).</p>
 *
 * <p>Reuse of pool / TLS terminate / routing / {@code BackendSessionReset} SPI
 * comes from {@link AbstractProtocolAdapter}; reset defaults to
 * {@link com.whosly.gateway.adapter.protocol.BackendSessionReset#none()} until a
 * SQL Server wire reset is implemented.</p>
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
     * Client-first, but PreLogin carries no user/database. Empty probe until
     * Login7 observation (P1) can fill {@code RoutingContext} safely.
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
            // P0: transparent duplex byte relay. Framing helpers exist for tests /
            // future observation; no interceptor pipeline yet (errors passthrough).
            new DuplexRelay(sessionId)
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
        // P0: no forged TDS ERROR token yet — close quietly (fail-closed for limit).
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
