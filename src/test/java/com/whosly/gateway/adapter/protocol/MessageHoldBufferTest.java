package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageHoldBufferTest {

    /**
     * Test framing: one message per newline, so boundaries are easy to read in
     * assertions. Real framing comes from the protocol layer (rule 2.10).
     */
    private static final MessageBounder NEWLINE_BOUNDER = (bytes, offset, length) -> {
        List<Integer> ends = new ArrayList<>();
        for (int index = 0; index < length; index++) {
            if (bytes[offset + index] == '\n') {
                ends.add(index + 1);
            }
        }
        return ends.stream().mapToInt(Integer::intValue).toArray();
    };

    private static MessageHoldBuffer buffer(RewriteLimits limits) {
        return new MessageHoldBuffer(NEWLINE_BOUNDER, limits);
    }

    @Test
    void holdsAnIncompleteMessageUntilItsLastByteArrives() {
        MessageHoldBuffer holdBuffer = buffer(RewriteLimits.defaults());

        MessageHoldBuffer.HeldWindow first = holdBuffer.append(bytes("ab"), 0, 2);

        assertThat(first.isEmpty()).isTrue();
        assertThat(first.completeBytes()).isZero();
        assertThat(holdBuffer.isHolding()).isTrue();
        assertThat(holdBuffer.heldBytes()).isEqualTo(2);

        MessageHoldBuffer.HeldWindow second = holdBuffer.append(bytes("c\n"), 0, 2);

        // The assembled message is "abc\n": four bytes, ending at offset 4.
        assertThat(second.messageCount()).isEqualTo(1);
        assertThat(second.completeBytes()).isEqualTo(4);
        assertThat(new String(second.bytes(), 0, second.completeBytes(), StandardCharsets.US_ASCII))
                .isEqualTo("abc\n");
        assertThat(holdBuffer.isHolding()).isFalse();
        assertThat(holdBuffer.heldBytes()).isZero();
    }

    @Test
    void reportsEveryCompleteMessageAndKeepsTheTail() {
        MessageHoldBuffer holdBuffer = buffer(RewriteLimits.defaults());

        MessageHoldBuffer.HeldWindow window = holdBuffer.append(bytes("a\nb\nc"), 0, 5);

        assertThat(window.messageEnds()).containsExactly(2, 4);
        assertThat(window.completeBytes()).isEqualTo(4);
        assertThat(holdBuffer.heldBytes()).isEqualTo(1);
    }

    @Test
    void rejectsAMessageLargerThanTheByteBound() {
        MessageHoldBuffer holdBuffer = buffer(new RewriteLimits(4, 1000));

        assertThatThrownBy(() -> holdBuffer.append(bytes("abcde"), 0, 5))
                .isInstanceOf(RewriteLimitException.class)
                .hasMessageContaining("exceeds the rewrite limit")
                .satisfies(error -> assertThat(((RewriteLimitException) error).getReason())
                        .isEqualTo(RewriteLimitException.Reason.MESSAGE_TOO_LARGE));
    }

    @Test
    void rejectsAnOversizedTailAfterACompleteMessage() {
        MessageHoldBuffer holdBuffer = buffer(new RewriteLimits(4, 1000));

        assertThatThrownBy(() -> holdBuffer.append(bytes("a\nbcdef"), 0, 7))
                .isInstanceOf(RewriteLimitException.class)
                .satisfies(error -> assertThat(((RewriteLimitException) error).getReason())
                        .isEqualTo(RewriteLimitException.Reason.MESSAGE_TOO_LARGE));
    }

    @Test
    void rejectsAPartialMessageHeldTooLong() throws Exception {
        MessageHoldBuffer holdBuffer = buffer(new RewriteLimits(1024, 1));

        holdBuffer.append(bytes("a"), 0, 1);
        Thread.sleep(5);

        assertThatThrownBy(() -> holdBuffer.append(bytes("b"), 0, 1))
                .isInstanceOf(RewriteLimitException.class)
                .hasMessageContaining("held for")
                .satisfies(error -> assertThat(((RewriteLimitException) error).getReason())
                        .isEqualTo(RewriteLimitException.Reason.HOLD_TIMEOUT));
    }

    @Test
    void doesNotCountTimeWhileNothingIsHeld() throws Exception {
        MessageHoldBuffer holdBuffer = buffer(new RewriteLimits(1024, 1));

        holdBuffer.append(bytes("a\n"), 0, 2);
        Thread.sleep(5);

        // The previous window ended on a message boundary, so no hold is in flight.
        assertThat(holdBuffer.append(bytes("b\n"), 0, 2).messageCount()).isEqualTo(1);
    }

    @Test
    void drainsTheHeldTailOnTeardown() {
        MessageHoldBuffer holdBuffer = buffer(RewriteLimits.defaults());

        holdBuffer.append(bytes("ab"), 0, 2);

        assertThat(new String(holdBuffer.drain(), StandardCharsets.US_ASCII)).isEqualTo("ab");
        assertThat(holdBuffer.isHolding()).isFalse();
        assertThat(holdBuffer.drain()).isEmpty();
    }

    @Test
    void returnsAnEmptyWindowForAnEmptyRead() {
        MessageHoldBuffer holdBuffer = buffer(RewriteLimits.defaults());

        assertThat(holdBuffer.append(new byte[0], 0, 0).isEmpty()).isTrue();
        assertThat(holdBuffer.isHolding()).isFalse();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
