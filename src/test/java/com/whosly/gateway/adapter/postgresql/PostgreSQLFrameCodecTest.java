package com.whosly.gateway.adapter.postgresql;

import com.whosly.gateway.adapter.protocol.ProtocolException;
import com.whosly.gateway.adapter.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgreSQLFrameCodecTest {

    private final PostgreSQLFrameCodec codec = new PostgreSQLFrameCodec();

    @Test
    void writesTypedMessageWithBigEndianLength() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        codec.write(ProtocolMessage.typed('Q', new byte[] {'S', 0}), output);

        assertThat(output.toByteArray()).containsExactly('Q', 0, 0, 0, 6, 'S', 0);
    }

    @Test
    void readsTypedMessage() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {'Q', 0, 0, 0, 6, 'S', 0});

        ProtocolMessage message = codec.read(input);

        assertThat(message.type()).contains('Q');
        assertThat(message.payload()).containsExactly('S', 0);
    }

    @Test
    void readsUntypedStartupMessage() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {0, 0, 0, 8, 4, (byte) 0xD2, 22, 47});

        ProtocolMessage message = codec.readStartupMessage(input);

        assertThat(message.type()).isEmpty();
        assertThat(message.payload()).containsExactly(4, (byte) 0xD2, 22, 47);
    }

    @Test
    void rejectsLengthSmallerThanFour() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {'Q', 0, 0, 0, 3});

        assertThatThrownBy(() -> codec.read(input))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("PostgreSQL message length");
    }

    @Test
    void rejectsTruncatedPayload() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {'Q', 0, 0, 0, 8, 'a'});

        assertThatThrownBy(() -> codec.read(input))
            .isInstanceOf(EOFException.class);
    }
}
