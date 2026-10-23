package com.whosly.gateway.adapter.sqlserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * MS-TDS DONE token ({@code 0xFD}): forge helpers + cleartext observation parse.
 *
 * <p>Forge uses TDS 7.2+ {@code DoneRowCount} as LONGLONG. Observation is
 * best-effort: truncated or non-DONE bytes yield empty rather than throwing
 * into the relay path.</p>
 */
public final class TdsDoneToken {

    public static final int TOKEN_TYPE = 0xFD;

    /** Status: this is the final DONE in the response. */
    public static final int STATUS_FINAL = 0x0000;
    /** Status: more DONE tokens follow for this request. */
    public static final int STATUS_MORE = 0x0001;
    /** Status: error occurred (combine with FINAL). */
    public static final int STATUS_ERROR = 0x0002;
    /** Status: transaction in progress. */
    public static final int STATUS_INXACT = 0x0004;
    /** Status: DoneRowCount is valid. */
    public static final int STATUS_COUNT = 0x0010;
    /** Status: Attention acknowledged (cancel completed on server). */
    public static final int STATUS_ATTN = 0x0020;
    /** Status: server error info follows (SRVERROR). */
    public static final int STATUS_SRVERROR = 0x0100;

    /** Wire size: type(1) + status(2) + curCmd(2) + rowCount(8). */
    public static final int WIRE_LENGTH = 1 + 2 + 2 + 8;

    private final int status;
    private final int curCmd;
    private final long doneRowCount;

    private TdsDoneToken(int status, int curCmd, long doneRowCount) {
        this.status = status;
        this.curCmd = curCmd;
        this.doneRowCount = doneRowCount;
    }

    public int status() {
        return status;
    }

    public int curCmd() {
        return curCmd;
    }

    public long doneRowCount() {
        return doneRowCount;
    }

    public boolean isFinal() {
        return (status & STATUS_MORE) == 0;
    }

    public boolean isError() {
        return (status & STATUS_ERROR) != 0;
    }

    public boolean isAttentionAck() {
        return (status & STATUS_ATTN) != 0;
    }

    public int wireLength() {
        return WIRE_LENGTH;
    }

    /**
     * Parses a DONE token at {@code offset} in a tabular payload.
     *
     * @return empty when the byte is not DONE or the buffer is too short
     */
    public static Optional<TdsDoneToken> tryParse(byte[] payload, int offset) {
        if (payload == null || offset < 0 || offset + WIRE_LENGTH > payload.length) {
            return Optional.empty();
        }
        if ((payload[offset] & 0xFF) != TOKEN_TYPE) {
            return Optional.empty();
        }
        int status = u16le(payload, offset + 1);
        int curCmd = u16le(payload, offset + 3);
        long rowCount = u64le(payload, offset + 5);
        return Optional.of(new TdsDoneToken(status, curCmd, rowCount));
    }

    /**
     * DONE with {@link #STATUS_ERROR} and zero row count (TDS 7.2+ LONGLONG).
     */
    public static byte[] encodeErrorFinal() {
        return encode(STATUS_ERROR, 0, 0L);
    }

    /**
     * DONE acknowledging Attention ({@link #STATUS_ATTN}), for tests.
     */
    public static byte[] encodeAttentionAck() {
        return encode(STATUS_ATTN, 0, 0L);
    }

    public static byte[] encode(int status, int curCmd, long doneRowCount) {
        ByteBuffer buf = ByteBuffer.allocate(WIRE_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) TOKEN_TYPE);
        buf.putShort((short) status);
        buf.putShort((short) curCmd);
        buf.putLong(doneRowCount);
        return buf.array();
    }

    private static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long u64le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24)
                | ((bytes[offset + 4] & 0xFFL) << 32)
                | ((bytes[offset + 5] & 0xFFL) << 40)
                | ((bytes[offset + 6] & 0xFFL) << 48)
                | ((bytes[offset + 7] & 0xFFL) << 56);
    }
}
