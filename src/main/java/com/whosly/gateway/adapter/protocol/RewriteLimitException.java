package com.whosly.gateway.adapter.protocol;

/**
 * A message could not be assembled within the configured rewrite limits.
 *
 * <p>Rejecting is deliberate. The alternative would be to forward the message
 * anyway, which means forwarding it without the rewrite the deployment asked
 * for: a result the client would read as "already masked" while the values were
 * in fact untouched (rule 8.2).</p>
 *
 * <p>The message carries only sizes and configured bounds: never payload bytes,
 * which may be the very data the rewrite was meant to hide.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class RewriteLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which bound was exceeded. */
    public enum Reason {
        /** The message is larger than {@link RewriteLimits#maxMessageBytes()}. */
        MESSAGE_TOO_LARGE,
        /** The message stayed incomplete longer than {@link RewriteLimits#maxHoldMillis()}. */
        HOLD_TIMEOUT
    }

    private final Reason reason;

    private RewriteLimitException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static RewriteLimitException messageTooLarge(long observedBytes, RewriteLimits limits) {
        return new RewriteLimitException(Reason.MESSAGE_TOO_LARGE,
                "message of " + observedBytes + " bytes exceeds the rewrite limit of "
                        + limits.maxMessageBytes() + " bytes");
    }

    public static RewriteLimitException holdTimeout(long heldMillis, RewriteLimits limits) {
        return new RewriteLimitException(Reason.HOLD_TIMEOUT,
                "partial message held for " + heldMillis + " ms exceeds the rewrite limit of "
                        + limits.maxHoldMillis() + " ms");
    }

    public Reason getReason() {
        return reason;
    }
}
