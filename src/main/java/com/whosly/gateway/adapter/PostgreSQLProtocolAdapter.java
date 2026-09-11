package com.whosly.gateway.adapter;

import com.whosly.gateway.adapter.postgresql.PostgreSQLDatabaseEventExtractor;
import com.whosly.gateway.adapter.postgresql.PostgreSQLFrameCodec;
import com.whosly.gateway.adapter.postgresql.PostgreSQLProtocolErrorMapper;
import com.whosly.gateway.adapter.postgresql.PostgreSQLSession;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficInspector;
import com.whosly.gateway.adapter.protocol.DuplexRelay;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
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
 *
 * <p>Accepts a client connection, opens the target connection and relays bytes
 * in both directions while a per-connection {@link PostgreSQLSession} records
 * observed protocol state. When the target cannot be reached the client gets a
 * PostgreSQL-native {@code ErrorResponse} instead of a bare TCP reset.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class PostgreSQLProtocolAdapter extends AbstractProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLProtocolAdapter.class);
    private static final String PROTOCOL_NAME = "PostgreSQL";
    private static final int DEFAULT_PORT = 5432;
    private static final Duration TARGET_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int FIRST_CLIENT_MESSAGE_TIMEOUT_MS = 5000;
    private static final PostgreSQLFrameCodec FRAME_CODEC = new PostgreSQLFrameCodec();
    private static final PostgreSQLProtocolErrorMapper GATEWAY_ERROR_MAPPER = new PostgreSQLProtocolErrorMapper();

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
        PostgreSQLSession session = new PostgreSQLSession(sessionId);

        Socket targetSocket;
        try {
            targetSocket = connectTarget();
        } catch (IOException e) {
            log.warn("PostgreSQL proxy session {} could not reach target {}:{}: {}",
                    sessionId, targetHost, targetPort, e.getMessage());
            sendStartupError(clientSocket, e);
            session.close();
            closeQuietly(clientSocket);
            return;
        }

        try (Socket target = targetSocket) {
            log.info("PostgreSQL proxy session {} connected {} to target {}:{}",
                    sessionId, clientSocket.getRemoteSocketAddress(), targetHost, targetPort);
            DatabaseTrafficInspector trafficInspector = new DatabaseTrafficInspector(
                    new PostgreSQLDatabaseEventExtractor(PROTOCOL_NAME, sessionId, false, session)::inspect,
                    databaseTrafficObserver,
                    databaseRiskPolicy);
            new DuplexRelay(sessionId, trafficInspector).relay(clientSocket, target);
        } catch (IOException e) {
            log.warn("PostgreSQL proxy session {} closed: {}", sessionId, e.getMessage());
        } finally {
            session.close();
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
    private static void sendStartupError(Socket clientSocket, IOException cause) {
        try {
            clientSocket.setSoTimeout(FIRST_CLIENT_MESSAGE_TIMEOUT_MS);
            ProtocolMessage first = FRAME_CODEC.readStartupMessage(clientSocket.getInputStream());
            int requestCode = requestCode(first);

            if (requestCode == PostgreSQLFrameCodec.CANCEL_REQUEST_CODE) {
                return;
            }
            if (requestCode == PostgreSQLFrameCodec.SSL_REQUEST_CODE
                    || requestCode == PostgreSQLFrameCodec.GSSENC_REQUEST_CODE) {
                clientSocket.getOutputStream().write('N');
                clientSocket.getOutputStream().flush();
                first = FRAME_CODEC.readStartupMessage(clientSocket.getInputStream());
                requestCode = requestCode(first);
            }
            if (requestCode == PostgreSQLFrameCodec.CANCEL_REQUEST_CODE) {
                return;
            }

            ProtocolMessage error = GATEWAY_ERROR_MAPPER.toErrorMessage(cause);
            FRAME_CODEC.write(error, clientSocket.getOutputStream());
            clientSocket.getOutputStream().flush();
        } catch (IOException | RuntimeException e) {
            log.debug("PostgreSQL proxy session failed to send gateway error: {}", e.getMessage());
        }
    }

    private static int requestCode(ProtocolMessage startupFamilyMessage) {
        byte[] payload = startupFamilyMessage.payload();
        return PostgreSQLFrameCodec.readInt4(payload, 0, payload.length);
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
