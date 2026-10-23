package com.whosly.gateway.adapter.sqlserver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TdsLogicalMessageTest {

    @Test
    void roundTripsSinglePacket() throws Exception {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        TdsLogicalMessage original = TdsLogicalMessage.singlePacket(
                TdsPacketType.PRELOGIN.getCode(), payload, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        original.writeTo(out);
        TdsLogicalMessage read = TdsLogicalMessage.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(read.type()).isEqualTo(TdsPacketType.PRELOGIN.getCode());
        assertThat(read.payload()).isEqualTo(payload);
        assertThat(read.wireBytes()).isEqualTo(original.wireBytes());
    }

    @Test
    void assemblesMultiPacketUntilEom() throws Exception {
        TdsFrameCodec codec = new TdsFrameCodec();
        byte[] part1 = codec.packet(TdsPacketType.PRELOGIN.getCode(), new byte[]{1, 2}, 1);
        // Clear EOM on first packet
        part1[1] = (byte) (part1[1] & ~TdsPacketStatus.EOM);
        byte[] part2 = codec.packet(TdsPacketType.PRELOGIN.getCode(), new byte[]{3}, 2);
        byte[] wire = new byte[part1.length + part2.length];
        System.arraycopy(part1, 0, wire, 0, part1.length);
        System.arraycopy(part2, 0, wire, part1.length, part2.length);

        TdsLogicalMessage msg = TdsLogicalMessage.read(new ByteArrayInputStream(wire));
        assertThat(msg.payload()).containsExactly(1, 2, 3);
        assertThat(msg.wireBytes()).hasSize(wire.length);
    }
}
