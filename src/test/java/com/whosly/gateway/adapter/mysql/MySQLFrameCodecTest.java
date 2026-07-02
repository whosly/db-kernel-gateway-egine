package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySQLFrameCodecTest {

    private final MySQLFrameCodec codec = new MySQLFrameCodec();

    @Test
    void writesPayloadLengthLittleEndianAndSequence() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        codec.write(ProtocolMessage.typed((char) 0x03, new byte[] {'S', 'E', 'L'}, 9), output);

        assertThat(output.toByteArray()).containsExactly(3, 0, 0, 9, 'S', 'E', 'L');
    }

    @Test
    void readsPacketIntoProtocolMessage() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {3, 0, 0, 2, 'a', 'b', 'c'});

        ProtocolMessage message = codec.read(input);

        assertThat(message.sequence()).hasValue(2);
        assertThat(message.payload()).containsExactly('a', 'b', 'c');
        assertThat(message.type()).isEmpty();
    }

    @Test
    void rejectsPayloadTooLargeForSinglePacket() {
        byte[] payload = new byte[0x1000000];

        assertThatThrownBy(() -> codec.write(ProtocolMessage.typed((char) 0x03, payload, 0), new ByteArrayOutputStream()))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("MySQL packet payload too large");
    }

    @Test
    void rejectsTruncatedPayload() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {4, 0, 0, 1, 'a', 'b'});

        assertThatThrownBy(() -> codec.read(input))
            .isInstanceOf(EOFException.class);
    }
}
