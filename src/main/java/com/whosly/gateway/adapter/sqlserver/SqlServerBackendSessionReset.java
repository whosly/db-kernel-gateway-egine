package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.BackendSessionReset;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SQL Server wire reset: RPC {@code sp_reset_connection}, then wait for a final
 * DONE / DONEPROC without ERROR.
 *
 * <p>Selected when {@code gateway.pool.reset-mode=protocol} and the proxy db type
 * is {@code sqlserver}/{@code mssql}. Failure closes the socket via the pool.</p>
 *
 * <p>This is the same procedure Microsoft drivers use for connection-pool reset.
 * Packet-status {@link TdsPacketStatus#RESET_CONNECTION} is an alternative used
 * on the next client request; the gateway has no pending client request at pool
 * release, so an explicit RPC is required.</p>
 */
public final class SqlServerBackendSessionReset implements BackendSessionReset {

    private static final Logger log = LoggerFactory.getLogger(SqlServerBackendSessionReset.class);

    private static final String PROC_NAME = "sp_reset_connection";
    private static final int DEFAULT_SO_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_BACKEND_PACKETS = 32;

    private final TdsFrameCodec codec;
    private final int soTimeoutMillis;

    public SqlServerBackendSessionReset() {
        this(new TdsFrameCodec(), DEFAULT_SO_TIMEOUT_MILLIS);
    }

    public SqlServerBackendSessionReset(TdsFrameCodec codec, int soTimeoutMillis) {
        this.codec = codec != null ? codec : new TdsFrameCodec();
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
            ProtocolMessage rpc = ProtocolMessage.typed(
                    (char) TdsPacketType.RPC.getCode(), buildSpResetConnectionPayload(), 1);
            codec.write(rpc, out);
            out.flush();
            return readResetOutcome(in);
        } catch (SocketTimeoutException e) {
            log.debug("sp_reset_connection timed out: {}", e.getMessage());
            throw e;
        } finally {
            try {
                connection.setSoTimeout(previousTimeout);
            } catch (IOException ignored) {
                // Socket may already be unusable; caller closes on failure.
            }
        }
    }

    /**
     * TDS 7.2+ RPC: empty ALL_HEADERS (total length 4) + proc name + flags 0.
     */
    static byte[] buildSpResetConnectionPayload() {
        byte[] nameUtf16 = PROC_NAME.getBytes(StandardCharsets.UTF_16LE);
        int nameChars = nameUtf16.length / 2;
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + nameUtf16.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(4); // ALL_HEADERS total length (header only)
        buf.putShort((short) nameChars);
        buf.put(nameUtf16);
        buf.putShort((short) 0); // OptionFlags
        return buf.array();
    }

    private boolean readResetOutcome(InputStream in) throws IOException {
        boolean sawError = false;
        for (int i = 0; i < MAX_BACKEND_PACKETS; i++) {
            ProtocolMessage message = codec.read(in);
            int type = message.type().map(c -> c & 0xFF).orElse(-1);
            if (type != TdsPacketType.TABULAR_RESULT.getCode()) {
                log.debug("sp_reset_connection: unexpected packet type 0x{}", Integer.toHexString(type));
                return false;
            }
            byte[] payload = message.payload();
            ByteArrayOutputStream logical = new ByteArrayOutputStream();
            logical.write(payload);
            // codec.read returns one physical packet; assemble until EOM via status — but
            // ProtocolMessage from codec.write/read uses EOM always on write; server may
            // fragment. Read additional packets while last status lacked EOM.
            // Our codec.read does not expose status; assume single-packet responses for
            // sp_reset_connection (typical). Scan tokens in this payload.
            TokenScan scan = scanTokens(logical.toByteArray());
            if (scan.error) {
                sawError = true;
            }
            if (scan.finalDone) {
                return !sawError;
            }
        }
        log.debug("sp_reset_connection: final DONE not seen within {} packets", MAX_BACKEND_PACKETS);
        return false;
    }

    static TokenScan scanTokens(byte[] payload) {
        boolean error = false;
        boolean finalDone = false;
        int cursor = 0;
        while (cursor < payload.length) {
            int token = payload[cursor] & 0xFF;
            if (token == TdsErrorToken.TOKEN_TYPE) {
                OptionalParse err = trySkipLengthPrefixed(payload, cursor);
                if (err == null) {
                    break;
                }
                error = true;
                cursor = err.next;
                continue;
            }
            if (token == TdsDoneToken.TOKEN_TYPE
                    || token == TdsTokenType.DONEPROC.getCode()
                    || token == TdsTokenType.DONEINPROC.getCode()) {
                if (cursor + TdsDoneToken.WIRE_LENGTH > payload.length) {
                    break;
                }
                int status = TdsColMetadata.u16le(payload, cursor + 1);
                boolean more = (status & TdsDoneToken.STATUS_MORE) != 0;
                cursor += TdsDoneToken.WIRE_LENGTH;
                if (!more && token != TdsTokenType.DONEINPROC.getCode()) {
                    // DONEINPROC with MORE clears intermediate; DONE/DONEPROC without MORE ends.
                    finalDone = true;
                    break;
                }
                if (!more && token == TdsTokenType.DONEINPROC.getCode()) {
                    // Intermediate; continue scanning.
                    continue;
                }
                continue;
            }
            OptionalParse skip = trySkipLengthPrefixed(payload, cursor);
            if (skip == null) {
                // Unknown token — advance one byte best-effort (same as extractor ERROR walk).
                cursor++;
                continue;
            }
            cursor = skip.next;
        }
        return new TokenScan(error, finalDone);
    }

    private static OptionalParse trySkipLengthPrefixed(byte[] payload, int offset) {
        if (offset + 3 > payload.length) {
            return null;
        }
        int len = TdsColMetadata.u16le(payload, offset + 1);
        int total = 1 + 2 + len;
        if (offset + total > payload.length) {
            return null;
        }
        return new OptionalParse(offset + total);
    }

    record TokenScan(boolean error, boolean finalDone) {
    }

    private static final class OptionalParse {
        final int next;

        OptionalParse(int next) {
            this.next = next;
        }
    }
}
