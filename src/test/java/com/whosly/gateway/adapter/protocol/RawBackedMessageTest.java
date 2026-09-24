package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawBackedMessageTest {

    @Test
    void exposesTheOriginalViewUntilItIsRewritten() {
        byte[] buffer = {1, 2, 3, 4};
        WireMessage message = RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, buffer, 1, 2);

        assertThat(message.direction()).isEqualTo(TrafficDirection.CLIENT_TO_TARGET);
        assertThat(message.mutated()).isFalse();
        assertThat(message.outputBytes()).isSameAs(buffer);
        assertThat(message.outputOffset()).isEqualTo(1);
        assertThat(message.outputLength()).isEqualTo(2);
    }

    @Test
    void keepsTheOriginalViewWhenAReplacementIsSet() {
        byte[] buffer = {1, 2, 3, 4};
        WireMessage message = RawBackedMessage.of(TrafficDirection.TARGET_TO_CLIENT, buffer, 1, 2);

        WireMessage rewritten = message.withReplacement(new byte[]{9});

        assertThat(rewritten.mutated()).isTrue();
        assertThat(rewritten.outputBytes()).containsExactly(9);
        assertThat(rewritten.outputOffset()).isZero();
        assertThat(rewritten.outputLength()).isEqualTo(1);
        assertThat(rewritten.originalBytes()).isSameAs(buffer);
        assertThat(rewritten.originalOffset()).isEqualTo(1);
        assertThat(rewritten.originalLength()).isEqualTo(2);

        // The message handed to the rewrite is left untouched.
        assertThat(message.mutated()).isFalse();
        assertThat(message.outputBytes()).isSameAs(buffer);
    }

    @Test
    void rejectsAViewOutsideTheReadBuffer() {
        byte[] buffer = {1, 2};

        assertThatThrownBy(() -> RawBackedMessage.of(TrafficDirection.CLIENT_TO_TARGET, buffer, 1, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
