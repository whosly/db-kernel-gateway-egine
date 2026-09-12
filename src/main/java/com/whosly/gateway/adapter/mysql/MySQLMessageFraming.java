package com.whosly.gateway.adapter.mysql;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL logical-packet boundaries inside a byte window.
 *
 * <p>Pure framing math. Every packet starts with a 3-byte little-endian payload
 * length, and a payload of exactly {@code 2^24 - 1} means the logical packet
 * continues in the next physical packet: the terminator is the first shorter
 * packet. This mirrors what the extractor does when it reassembles them, so a
 * rewrite boundary is a logical packet, never a fragment of one.</p>
 *
 * <p>Session state stays out of this class. Whether framing is available at all
 * is the extractor's decision: an encrypted or compressed session has no
 * cleartext packet headers to read (rule 2.10).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MySQLMessageFraming {

    private MySQLMessageFraming() {
    }

    /**
     * Finds the end of every complete logical packet in the window.
     *
     * @param bytes  buffer holding the window, which must start on a packet boundary
     * @param offset window start
     * @param length window length
     * @return end offsets relative to {@code offset}, ascending; see
     *         {@link com.whosly.gateway.adapter.protocol.MessageBounder}
     */
    public static int[] completeMessageEnds(byte[] bytes, int offset, int length) {
        List<Integer> ends = new ArrayList<>();
        int endExclusive = offset + length;
        int cursor = offset;
        while (true) {
            int payloadLength = MySQLFrameCodec.payloadLength(bytes, cursor, endExclusive);
            if (payloadLength < 0) {
                // The next header is not complete.
                break;
            }
            int next = cursor + MySQLFrameCodec.HEADER_LENGTH + payloadLength;
            if (next > endExclusive) {
                // The payload is not complete yet.
                break;
            }
            cursor = next;
            if (payloadLength == MySQLFrameCodec.MAX_PAYLOAD_LENGTH) {
                // A maximum-size payload means the logical packet continues: the end is
                // the first packet that is shorter, so this is not a boundary.
                continue;
            }
            ends.add(cursor - offset);
        }
        return ends.stream().mapToInt(Integer::intValue).toArray();
    }
}
