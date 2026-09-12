package com.whosly.gateway.adapter.mysql;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLMessageFramingTest {

    private static final MySQLFrameCodec CODEC = new MySQLFrameCodec();

    @Test
    void reportsOneBoundaryPerPacket() {
        byte[] first = packet("abc", 0);
        byte[] second = packet("de", 1);
        byte[] window = concat(first, second);

        assertThat(MySQLMessageFraming.completeMessageEnds(window, 0, window.length))
                .containsExactly(first.length, window.length);
    }

    @Test
    void waitsForTheRestOfAnIncompleteHeader() {
        byte[] packet = packet("abc", 0);

        assertThat(MySQLMessageFraming.completeMessageEnds(packet, 0, 3)).isEmpty();
    }

    @Test
    void waitsForTheRestOfAnIncompletePayload() {
        byte[] packet = packet("abcdef", 0);

        assertThat(MySQLMessageFraming.completeMessageEnds(packet, 0, packet.length - 1)).isEmpty();
        assertThat(MySQLMessageFraming.completeMessageEnds(packet, 0, packet.length))
                .containsExactly(packet.length);
    }

    @Test
    void reportsOffsetsRelativeToTheWindowStart() {
        byte[] first = packet("abc", 0);
        byte[] second = packet("de", 1);
        byte[] window = concat(first, second);

        // A window that starts on the second message reports offsets from its own start.
        assertThat(MySQLMessageFraming.completeMessageEnds(window, first.length, second.length))
                .containsExactly(second.length);
    }

    @Test
    void treatsAMaximumSizePayloadAsAContinuingLogicalPacket() {
        // A payload of exactly 2^24 - 1 is not a message end: MySQL continues it in
        // the next packet, so the boundary is the first shorter packet.
        int continued = MySQLFrameCodec.HEADER_LENGTH + MySQLFrameCodec.MAX_PAYLOAD_LENGTH;
        int terminator = MySQLFrameCodec.HEADER_LENGTH + 2;
        byte[] window = new byte[continued + terminator];
        window[0] = (byte) 0xFF;
        window[1] = (byte) 0xFF;
        window[2] = (byte) 0xFF;
        window[continued] = 0x02;
        window[continued + 1] = 0x00;
        window[continued + 2] = 0x00;
        window[continued + 3] = 0x01;

        assertThat(MySQLMessageFraming.completeMessageEnds(window, 0, window.length))
                .containsExactly(window.length);
        // Until the terminator arrives the logical packet is still incomplete.
        assertThat(MySQLMessageFraming.completeMessageEnds(window, 0, continued)).isEmpty();
    }

    @Test
    void reportsNothingForAnEmptyWindow() {
        assertThat(MySQLMessageFraming.completeMessageEnds(new byte[0], 0, 0)).isEmpty();
    }

    private static byte[] packet(String payload, int sequenceId) {
        return CODEC.packet(payload.getBytes(StandardCharsets.US_ASCII), sequenceId);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.writeBytes(first);
        outputStream.writeBytes(second);
        return outputStream.toByteArray();
    }
}
