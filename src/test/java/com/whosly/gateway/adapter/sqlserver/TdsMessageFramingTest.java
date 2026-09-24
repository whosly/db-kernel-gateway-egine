package com.whosly.gateway.adapter.sqlserver;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TdsMessageFramingTest {

    private static final TdsFrameCodec CODEC = new TdsFrameCodec();

    @Test
    void reportsOneBoundaryPerEomPacket() {
        byte[] first = CODEC.packet(TdsPacketType.PRELOGIN.getCode(), "abc".getBytes(StandardCharsets.UTF_8), 1);
        byte[] second = CODEC.packet(TdsPacketType.TDS7_LOGIN.getCode(), "de".getBytes(StandardCharsets.UTF_8), 2);
        byte[] window = concat(first, second);

        assertThat(TdsMessageFraming.completeMessageEnds(window, 0, window.length))
                .containsExactly(first.length, window.length);
    }

    @Test
    void waitsForIncompleteHeader() {
        byte[] packet = CODEC.packet(TdsPacketType.PRELOGIN.getCode(), "abc".getBytes(StandardCharsets.UTF_8), 1);
        assertThat(TdsMessageFraming.completeMessageEnds(packet, 0, 4)).isEmpty();
    }

    @Test
    void waitsForIncompletePayload() {
        byte[] packet = CODEC.packet(TdsPacketType.PRELOGIN.getCode(), "abcdef".getBytes(StandardCharsets.UTF_8), 1);
        assertThat(TdsMessageFraming.completeMessageEnds(packet, 0, packet.length - 1)).isEmpty();
        assertThat(TdsMessageFraming.completeMessageEnds(packet, 0, packet.length))
                .containsExactly(packet.length);
    }

    @Test
    void spansMultiplePhysicalPacketsUntilEom() {
        byte[] part1 = packetWithoutEom(TdsPacketType.SQL_BATCH.getCode(), "hello".getBytes(StandardCharsets.UTF_8), 1);
        byte[] part2 = CODEC.packet(TdsPacketType.SQL_BATCH.getCode(), "world".getBytes(StandardCharsets.UTF_8), 2);
        byte[] window = concat(part1, part2);

        assertThat(TdsMessageFraming.completeMessageEnds(window, 0, part1.length)).isEmpty();
        assertThat(TdsMessageFraming.completeMessageEnds(window, 0, window.length))
                .containsExactly(window.length);
    }

    @Test
    void packetLengthIsBigEndianTotalIncludingHeader() {
        byte[] packet = CODEC.packet(TdsPacketType.PRELOGIN.getCode(), new byte[10], 1);
        assertThat(TdsFrameCodec.packetLength(packet, 0, packet.length))
                .isEqualTo(TdsFrameCodec.HEADER_LENGTH + 10);
        assertThat(TdsFrameCodec.type(packet, 0, packet.length))
                .isEqualTo(TdsPacketType.PRELOGIN.getCode());
        assertThat(TdsPacketStatus.isEom(TdsFrameCodec.status(packet, 0, packet.length))).isTrue();
    }

    private static byte[] packetWithoutEom(int type, byte[] payload, int packetId) {
        int packetLength = TdsFrameCodec.HEADER_LENGTH + payload.length;
        byte[] frame = new byte[packetLength];
        frame[0] = (byte) (type & 0xFF);
        frame[1] = (byte) TdsPacketStatus.NORMAL;
        frame[2] = (byte) ((packetLength >> 8) & 0xFF);
        frame[3] = (byte) (packetLength & 0xFF);
        frame[6] = (byte) (packetId & 0xFF);
        System.arraycopy(payload, 0, frame, TdsFrameCodec.HEADER_LENGTH, payload.length);
        return frame;
    }

    private static byte[] concat(byte[]... parts) {
        int total = Arrays.stream(parts).mapToInt(p -> p.length).sum();
        byte[] out = new byte[total];
        int cursor = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, cursor, part.length);
            cursor += part.length;
        }
        return out;
    }
}
