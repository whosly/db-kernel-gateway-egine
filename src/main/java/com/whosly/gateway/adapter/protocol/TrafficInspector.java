package com.whosly.gateway.adapter.protocol;

/**
 * Inspects traffic while preserving transparent byte forwarding semantics.
 *
 * <p>An inspector returns a {@link TrafficDecision}: forward the message as it
 * arrived, forward a rewritten payload, deny it, or close the connection. Byte
 * forwarding stays the default, and only an explicit rewrite changes it
 * (rule 2.10).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface TrafficInspector {

    TrafficDecision inspect(WireMessage message);

    /**
     * Message boundaries the inspector needs for a direction, or {@code null}
     * when it accepts whatever bytes a read produced.
     *
     * <p>A non-null bounder makes the relay assemble whole messages and hold the
     * unfinished tail, bounded by {@link RewriteLimits}. Returning {@code null} is
     * the transparent default: every read is forwarded immediately.</p>
     *
     * @param direction direction whose framing is needed
     * @return the bounder, or {@code null} for immediate forwarding
     */
    default MessageBounder messageBounder(TrafficDirection direction) {
        return null;
    }

    /** Forwards every message unchanged. */
    static TrafficInspector passThrough() {
        return message -> TrafficDecision.forward(message);
    }
}
