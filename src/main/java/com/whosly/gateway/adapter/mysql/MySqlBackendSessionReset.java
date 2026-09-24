package com.whosly.gateway.adapter.mysql;

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

/**
 * MySQL wire reset: sends {@link MySQLCommandType#COM_RESET_CONNECTION} and
 * expects an OK packet before the socket may return to the idle pool.
 *
 * <p>Selected when {@code gateway.pool.reset-mode=protocol} and the proxy db type
 * is MySQL. Failure (ERR, unexpected payload, timeout, IO) returns {@code false}
 * or throws so {@link com.whosly.gateway.adapter.protocol.PooledBackendProvider}
 * closes the socket.</p>
 *
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_com_reset_connection.html">
 *     COM_RESET_CONNECTION</a>
 */
public final class MySqlBackendSessionReset implements BackendSessionReset {

    private static final Logger log = LoggerFactory.getLogger(MySqlBackendSessionReset.class);

    private static final int OK_PACKET_HEADER = 0x00;
    private static final int ERR_PACKET_HEADER = 0xFF;
    private static final int DEFAULT_SO_TIMEOUT_MILLIS = 5_000;

    private final MySQLFrameCodec codec;
    private final int soTimeoutMillis;

    public MySqlBackendSessionReset() {
        this(new MySQLFrameCodec(), DEFAULT_SO_TIMEOUT_MILLIS);
    }

    public MySqlBackendSessionReset(MySQLFrameCodec codec, int soTimeoutMillis) {
        this.codec = codec != null ? codec : new MySQLFrameCodec();
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
            ProtocolMessage command = ProtocolMessage.untyped(
                    new byte[]{(byte) MySQLCommandType.COM_RESET_CONNECTION.getCode()}, 0);
            codec.write(command, out);
            out.flush();

            ProtocolMessage response = codec.read(in);
            byte[] payload = response.payload();
            if (payload.length == 0) {
                log.debug("COM_RESET_CONNECTION: empty response payload");
                return false;
            }
            int header = payload[0] & 0xFF;
            if (header == OK_PACKET_HEADER) {
                return true;
            }
            if (header == ERR_PACKET_HEADER) {
                log.debug("COM_RESET_CONNECTION: server returned ERR");
                return false;
            }
            log.debug("COM_RESET_CONNECTION: unexpected header 0x{}", Integer.toHexString(header));
            return false;
        } catch (SocketTimeoutException e) {
            log.debug("COM_RESET_CONNECTION timed out: {}", e.getMessage());
            throw e;
        } finally {
            try {
                connection.setSoTimeout(previousTimeout);
            } catch (IOException ignored) {
                // Socket may already be unusable; caller closes on failure.
            }
        }
    }
}
