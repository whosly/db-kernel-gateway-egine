package com.whosly.gateway.adapter.protocol;

/**
 * Bounds on the bytes the gateway may hold back while assembling a message.
 *
 * <p>Forwarding must not be delayed for the sake of observation (rule 2.10), so
 * the data path forwards every read immediately unless a rewrite needs a whole
 * message. Assembling that message means holding its earlier bytes until the
 * last one arrives, which is the one accepted delay, and it is bounded here: a
 * message larger than {@link #maxMessageBytes()} or a partial message held
 * longer than {@link #maxHoldMillis()} is rejected instead of forwarded
 * unmasked, because forwarding it would silently skip the rewrite
 * (rule 8.2).</p>
 *
 * <p>Both bounds are configurable because the right value depends on the
 * deployment: batch statements and large {@code DataRow} messages push the byte
 * bound up, while latency-sensitive paths push the time bound down.</p>
 *
 * @param maxMessageBytes largest single protocol message the gateway will hold
 *                        while assembling it; must be positive
 * @param maxHoldMillis   longest a partial message may be held before it is
 *                        rejected; must be positive
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public record RewriteLimits(int maxMessageBytes, long maxHoldMillis) {

    /** 1 MiB: larger than any ordinary statement or row, small enough to bound memory. */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 1024 * 1024;

    /** 1 second: the ceiling on the delay a rewrite may add. */
    public static final long DEFAULT_MAX_HOLD_MILLIS = 1000L;

    public RewriteLimits {
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
        if (maxHoldMillis <= 0) {
            throw new IllegalArgumentException("maxHoldMillis must be positive");
        }
    }

    /** The documented defaults: 1 MiB per message, 1 second of hold time. */
    public static RewriteLimits defaults() {
        return new RewriteLimits(DEFAULT_MAX_MESSAGE_BYTES, DEFAULT_MAX_HOLD_MILLIS);
    }

    /** True when an assembled message of this size is over the byte bound. */
    public boolean exceedsMessageSize(long bytes) {
        return bytes > maxMessageBytes;
    }

    /** True when a partial message held for this long is over the time bound. */
    public boolean exceedsHoldTime(long heldMillis) {
        return heldMillis > maxHoldMillis;
    }
}
