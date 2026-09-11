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

    /** Forwards every message unchanged. */
    static TrafficInspector passThrough() {
        return message -> TrafficDecision.forward(message);
    }
}
