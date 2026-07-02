package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.parser.DruidSqlParser;
import com.whosly.gateway.parser.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;

/**
 * PostgreSQL transparent protocol proxy adapter.
 */
public class PostgreSQLProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "PostgreSQL";
    private static final int DEFAULT_PORT = 5432;
    private static final Duration TARGET_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public PostgreSQLProtocolAdapter() {
        super(PROTOCOL_NAME, DEFAULT_PORT);
    }

    @Override
    protected SqlParser createSqlParser() {
        return new DruidSqlParser();
    }

    @Override
    protected void handleClientConnection(Socket clientSocket) {
        String sessionId = "postgresql-" + UUID.randomUUID();
        try (Socket targetSocket = connectTarget()) {
            log.info("PostgreSQL proxy session {} connected {} to target {}:{}",
                    sessionId, clientSocket.getRemoteSocketAddress(), targetHost, targetPort);
            new DuplexRelay(sessionId).relay(clientSocket, targetSocket);
        } catch (IOException e) {
            log.warn("PostgreSQL proxy session {} closed: {}", sessionId, e.getMessage());
        } finally {
            closeQuietly(clientSocket);
        }
    }

    private Socket connectTarget() throws IOException {
        Socket targetSocket = new Socket();
        targetSocket.setTcpNoDelay(true);
        targetSocket.connect(new InetSocketAddress(targetHost, targetPort),
                Math.toIntExact(TARGET_CONNECT_TIMEOUT.toMillis()));
        return targetSocket;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
