package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.adapter.protocol.SqlTrafficInspector;
import com.whosly.gateway.adapter.mysql.MySQLSqlEventExtractor;
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
 * MySQL transparent protocol proxy adapter.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class MySqlProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MySqlProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "MySQL";
    private static final int DEFAULT_PORT = 3307;
    private static final Duration TARGET_CONNECT_TIMEOUT = Duration.ofSeconds(10);

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
        try (Socket targetSocket = connectTarget()) {
            log.info("MySQL proxy session {} connected {} to target {}:{}",
                    sessionId, clientSocket.getRemoteSocketAddress(), targetHost, targetPort);
            SqlTrafficInspector trafficInspector = new SqlTrafficInspector(
                    new MySQLSqlEventExtractor(PROTOCOL_NAME, sessionId, false)::inspect,
                    sqlTrafficObserver,
                    sqlRiskPolicy);
            new DuplexRelay(sessionId, trafficInspector).relay(clientSocket, targetSocket);
        } catch (IOException e) {
            log.warn("MySQL proxy session {} closed: {}", sessionId, e.getMessage());
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
