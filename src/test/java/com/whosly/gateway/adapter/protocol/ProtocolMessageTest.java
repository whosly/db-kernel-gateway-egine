package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolMessageTest {

    @Test
    void copiesPayloadOnCreateAndRead() {
        byte[] payload = new byte[] {1, 2, 3};
        ProtocolMessage message = ProtocolMessage.typed('Q', payload, 7);

        payload[0] = 9;
        byte[] returnedPayload = message.payload();
        returnedPayload[1] = 8;

        assertThat(message.type()).contains('Q');
        assertThat(message.sequence()).hasValue(7);
        assertThat(message.payload()).containsExactly(1, 2, 3);
    }

    @Test
    void supportsUntypedStartupMessages() {
        ProtocolMessage message = ProtocolMessage.untyped(new byte[] {0, 3, 0, 0});

        assertThat(message.type()).isEmpty();
        assertThat(message.sequence()).isEmpty();
        assertThat(message.payload()).containsExactly(0, 3, 0, 0);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> ProtocolMessage.untyped(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
    }
}
