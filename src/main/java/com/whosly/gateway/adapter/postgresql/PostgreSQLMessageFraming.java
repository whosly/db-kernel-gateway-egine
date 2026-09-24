package com.whosly.gateway.adapter.postgresql;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL message boundaries inside a byte window.
 *
 * <p>Pure framing math for the three shapes a PostgreSQL stream can have:</p>
 * <ul>
 *   <li>{@link Framing#TYPED}: {@code [1-byte type][int4 length]}, where the length
 *       includes its own four bytes but not the type byte.</li>
 *   <li>{@link Framing#STARTUP_FAMILY}: the first message has no type byte
 *       ({@code [int4 length]} only), everything after it is typed. Startup,
 *       SSLRequest, GSSENCRequest and CancelRequest all use this shape.</li>
 *   <li>{@link Framing#ENCRYPTION_RESPONSE}: a single byte, the backend's answer
 *       to SSLRequest/GSSENCRequest.</li>
 * </ul>
 *
 * <p>A length that cannot be valid returns {@code null} instead of guessing, which
 * tells the caller to stop holding bytes rather than invent a boundary. Session
 * state stays in the extractor; this class only converts a known shape into
 * offsets.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class PostgreSQLMessageFraming {

    /** Which framing shape a stream is currently using. */
    public enum Framing {
        /** Every message carries a type byte. */
        TYPED,
        /** The leading message is untyped; the rest are typed. */
        STARTUP_FAMILY,
        /** A lone byte: the backend's encryption-encoding answer. */
        ENCRYPTION_RESPONSE
    }

    private PostgreSQLMessageFraming() {
    }

    /**
     * Finds the end of every complete message in the window.
     *
     * @param bytes   buffer holding the window, which must start on a message boundary
     * @param offset  window start
     * @param length  window length
     * @param framing framing shape of the stream
     * @return end offsets relative to {@code offset}, ascending, or {@code null}
     *         when the window contains a length that cannot be valid
     */
    public static int[] completeMessageEnds(byte[] bytes, int offset, int length, Framing framing) {
        if (framing == Framing.ENCRYPTION_RESPONSE) {
            return length > 0 ? new int[]{1} : new int[0];
        }

        List<Integer> ends = new ArrayList<>();
        int endExclusive = offset + length;
        int cursor = offset;
        boolean startupPending = framing == Framing.STARTUP_FAMILY;
        while (true) {
            if (startupPending) {
                if (endExclusive - cursor < PostgreSQLFrameCodec.UNTYPED_HEADER_LENGTH) {
                    break;
                }
                int messageLength = PostgreSQLFrameCodec.readInt4(bytes, cursor, endExclusive);
                if (messageLength < PostgreSQLFrameCodec.MIN_MESSAGE_LENGTH) {
                    return null;
                }
                int next = cursor + messageLength;
                if (next > endExclusive) {
                    break;
                }
                cursor = next;
                ends.add(cursor - offset);
                startupPending = false;
                continue;
            }

            if (endExclusive - cursor < PostgreSQLFrameCodec.TYPED_HEADER_LENGTH) {
                break;
            }
            int messageLength = PostgreSQLFrameCodec.readInt4(bytes, cursor + 1, endExclusive);
            if (messageLength < PostgreSQLFrameCodec.MIN_MESSAGE_LENGTH) {
                return null;
            }
            int next = cursor + 1 + messageLength;
            if (next > endExclusive) {
                break;
            }
            cursor = next;
            ends.add(cursor - offset);
        }
        return ends.stream().mapToInt(Integer::intValue).toArray();
    }
}
