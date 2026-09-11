package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TdsFrameCodecTest {

    private final TdsFrameCodec codec = new TdsFrameCodec();

    @Test
    void readsTdsPacketHeaderAndPayload() throws Exception {
        byte[] packet = new byte[]{
                0x01, 0x01, 0x00, 0x0c, 0x12, 0x34, 0x07, 0x00,
                's', 'q', 'l', 0x00
        };

        TdsPacket decoded = codec.readPacket(new ByteArrayInputStream(packet));

        assertThat(decoded.type()).isEqualTo(TdsPacketType.SQL_BATCH);
        assertThat(decoded.status()).isEqualTo(TdsPacket.STATUS_END_OF_MESSAGE);
        assertThat(decoded.spid()).isEqualTo(0x1234);
        assertThat(decoded.packetId()).isEqualTo(7);
        assertThat(decoded.window()).isZero();
        assertThat(decoded.payload()).containsExactly('s', 'q', 'l', 0x00);
    }

    @Test
    void writesTdsPacketWithBigEndianLength() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        codec.writePacket(new TdsPacket(TdsPacketType.SQL_BATCH,
                TdsPacket.STATUS_END_OF_MESSAGE, 0, 3, 0, new byte[]{'a', 'b'}), outputStream);

        assertThat(outputStream.toByteArray()).containsExactly(
                0x01, 0x01, 0x00, 0x0a, 0x00, 0x00, 0x03, 0x00, 'a', 'b');
    }

    @Test
    void rejectsLengthSmallerThanHeader() {
        byte[] packet = new byte[]{0x01, 0x01, 0x00, 0x07, 0, 0, 1, 0};

        assertThatThrownBy(() -> codec.readPacket(new ByteArrayInputStream(packet)))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("TDS packet length");
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] packet = new byte[]{0x01, 0x01, 0x00, 0x0a, 0, 0, 1, 0, 'a'};

        assertThatThrownBy(() -> codec.readPacket(new ByteArrayInputStream(packet)))
                .isInstanceOf(EOFException.class);
    }
}
