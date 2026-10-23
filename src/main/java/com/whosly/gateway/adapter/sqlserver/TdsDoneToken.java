package com.whosly.gateway.adapter.sqlserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal MS-TDS DONE token ({@code 0xFD}) builder for gateway-forged error replies.
 *
 * <p>Observation does not require DONE decode in this slice. Forge uses TDS 7.2+
 * {@code DoneRowCount} as LONGLONG.</p>
 */
public final class TdsDoneToken {

    public static final int TOKEN_TYPE = 0xFD;

    /** Status: this is the final DONE in the response. */
    public static final int STATUS_FINAL = 0x0000;
    /** Status: error occurred (combine with FINAL). */
    public static final int STATUS_ERROR = 0x0002;

    private TdsDoneToken() {
    }

    /**
     * DONE with {@link #STATUS_ERROR} and zero row count (TDS 7.2+ LONGLONG).
     */
    public static byte[] encodeErrorFinal() {
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + 2 + 8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) TOKEN_TYPE);
        buf.putShort((short) STATUS_ERROR);
        buf.putShort((short) 0); // CurCmd
        buf.putLong(0L); // DoneRowCount
        return buf.array();
    }
}
