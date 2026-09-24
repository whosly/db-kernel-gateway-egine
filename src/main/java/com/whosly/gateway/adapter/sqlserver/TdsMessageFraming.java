package com.whosly.gateway.adapter.sqlserver;

import java.util.ArrayList;
import java.util.List;

/**
 * TDS logical-message boundaries inside a byte window.
 *
 * <p>A TDS message may span multiple physical packets; the last packet of a
 * message has the {@link TdsPacketStatus#EOM} bit set. Rewrite / observation
 * boundaries are complete logical messages, never a fragment.</p>
 */
public final class TdsMessageFraming {

    private TdsMessageFraming() {
    }

    /**
     * Finds the end of every complete logical TDS message in the window.
     *
     * @param bytes  buffer holding the window, which must start on a packet boundary
     * @param offset window start
     * @param length window length
     * @return end offsets relative to {@code offset}, ascending
     */
    public static int[] completeMessageEnds(byte[] bytes, int offset, int length) {
        List<Integer> ends = new ArrayList<>();
        int endExclusive = offset + length;
        int cursor = offset;
        while (true) {
            int packetLength = TdsFrameCodec.packetLength(bytes, cursor, endExclusive);
            if (packetLength < 0) {
                break;
            }
            if (packetLength < TdsFrameCodec.MIN_PACKET_LENGTH) {
                break;
            }
            int next = cursor + packetLength;
            if (next > endExclusive) {
                break;
            }
            int status = TdsPacketStatus.EOM; // default if header incomplete — but we have full header
            status = TdsFrameCodec.status(bytes, cursor, endExclusive);
            cursor = next;
            if (!TdsPacketStatus.isEom(status)) {
                // Logical message continues in the next physical packet.
                continue;
            }
            ends.add(cursor - offset);
        }
        return ends.stream().mapToInt(Integer::intValue).toArray();
    }
}
