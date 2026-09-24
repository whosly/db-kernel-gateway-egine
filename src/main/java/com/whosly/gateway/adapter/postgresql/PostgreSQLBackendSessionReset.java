package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * PostgreSQL wire reset: sends a simple {@code Query} with {@code DISCARD ALL}
 * and waits for {@code ReadyForQuery} before the socket may return to the idle
 * pool.
 *
 * <p>{@code DISCARD ALL} clears temporary tables, prepared statements, portals,
 * listens, and session GUCs that would leak across clients. Selected when
 * {@code gateway.pool.reset-mode=protocol} and the proxy db type is PostgreSQL.
 * An {@code ErrorResponse} before {@code ReadyForQuery}, timeout, or IO failure
 * causes the pool to close the socket.</p>
 *
 * @see <a href="https://www.postgresql.org/docs/current/sql-discard.html">DISCARD</a>
 */
public final class PostgreSQLBackendSessionReset implements BackendSessionReset {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLBackendSessionReset.class);

    private static final String DISCARD_ALL = "DISCARD ALL";
    private static final int DEFAULT_SO_TIMEOUT_MILLIS = 5_000;
    /** Safety cap: DISCARD ALL should finish in a few messages. */
    private static final int MAX_BACKEND_MESSAGES = 64;

    private final PostgreSQLFrameCodec codec;
    private final int soTimeoutMillis;

    public PostgreSQLBackendSessionReset() {
        this(new PostgreSQLFrameCodec(), DEFAULT_SO_TIMEOUT_MILLIS);
    }

    public PostgreSQLBackendSessionReset(PostgreSQLFrameCodec codec, int soTimeoutMillis) {
        this.codec = codec != null ? codec : new PostgreSQLFrameCodec();
        this.soTimeoutMillis = Math.max(0, soTimeoutMillis);
    }

    @Override
    public boolean reset(Socket connection, SessionSnapshot snapshot) throws IOException {
        if (connection == null || connection.isClosed()) {
            return false;
        }
        int previousTimeout = connection.getSoTimeout();
        try {
            if (soTimeoutMillis > 0) {
                connection.setSoTimeout(soTimeoutMillis);
            }
            OutputStream out = connection.getOutputStream();
            InputStream in = connection.getInputStream();

            byte[] sql = (DISCARD_ALL + '\0').getBytes(StandardCharsets.UTF_8);
            codec.write(ProtocolMessage.typed(PostgreSQLMessageType.QUERY.getCode(), sql), out);
            out.flush();

            boolean sawError = false;
            for (int i = 0; i < MAX_BACKEND_MESSAGES; i++) {
                ProtocolMessage message = codec.read(in);
                char type = message.type().orElse('\0');
                if (type == PostgreSQLBackendMessageType.ERROR_RESPONSE.getCode()) {
                    sawError = true;
                    log.debug("DISCARD ALL: backend ErrorResponse");
                    continue;
                }
                if (type == PostgreSQLBackendMessageType.READY_FOR_QUERY.getCode()) {
                    return !sawError;
                }
                // CommandComplete, NoticeResponse, etc. are expected and ignored.
            }
            log.debug("DISCARD ALL: ReadyForQuery not seen within {} messages", MAX_BACKEND_MESSAGES);
            return false;
        } catch (SocketTimeoutException e) {
            log.debug("DISCARD ALL timed out: {}", e.getMessage());
            throw e;
        } finally {
            try {
                connection.setSoTimeout(previousTimeout);
            } catch (IOException ignored) {
            }
        }
    }
}
