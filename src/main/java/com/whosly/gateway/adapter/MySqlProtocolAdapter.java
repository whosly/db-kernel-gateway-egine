package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.mysql.MySQLDatabaseEventExtractor;
import com.whosly.gateway.adapter.mysql.MySQLFrameCodec;
import com.whosly.gateway.adapter.mysql.MySQLResultSetMaskingInterceptor;
import com.whosly.gateway.adapter.mysql.MySQLSession;
import com.whosly.gateway.adapter.mysql.MySqlGatewayErrorMapper;
import com.whosly.gateway.adapter.protocol.BackendProvider;
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

    @Override
    protected void handleClientConnection(Socket clientSocket) {
        String sessionId = "mysql-" + UUID.randomUUID();
        MySQLSession session = new MySQLSession(sessionId);
        registerSession(session);

        BackendProvider backendProvider = backendProvider();
        Socket targetSocket;
        try {
            targetSocket = backendProvider.acquire(RoutingContext.empty());
        } catch (IOException e) {
            log.warn("MySQL proxy session {} could not reach target {}:{}: {}",
                    sessionId, targetHost, targetPort, e.getMessage());
            sendGatewayError(clientSocket, e);
            session.close();
            unregisterSession(session);
            closeQuietly(clientSocket);
            return;
        }

        try {
            log.info("MySQL proxy session {} connected {} to target {}:{}",
                    sessionId, clientSocket.getRemoteSocketAddress(), targetHost, targetPort);
            MySQLDatabaseEventExtractor extractor =
                    new MySQLDatabaseEventExtractor(PROTOCOL_NAME, sessionId, false, session);
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
                    .relay(clientSocket, targetSocket);
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
