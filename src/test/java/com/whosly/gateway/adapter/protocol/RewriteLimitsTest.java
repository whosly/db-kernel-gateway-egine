package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteLimitsTest {

    @Test
    void defaultsToOneMebibyteAndOneSecond() {
        RewriteLimits limits = RewriteLimits.defaults();

        assertThat(limits.maxMessageBytes()).isEqualTo(1024 * 1024);
        assertThat(limits.maxHoldMillis()).isEqualTo(1000L);
    }

    @Test
    void treatsTheBoundItselfAsAllowed() {
        RewriteLimits limits = new RewriteLimits(8, 10);

        assertThat(limits.exceedsMessageSize(8)).isFalse();
        assertThat(limits.exceedsMessageSize(9)).isTrue();
        assertThat(limits.exceedsHoldTime(10)).isFalse();
        assertThat(limits.exceedsHoldTime(11)).isTrue();
    }

    @Test
    void rejectsNonPositiveBounds() {
        assertThatThrownBy(() -> new RewriteLimits(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessageBytes");
        assertThatThrownBy(() -> new RewriteLimits(8, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHoldMillis");
    }
}
