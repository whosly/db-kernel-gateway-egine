package com.whosly.gateway.adapter.protocol;

import java.util.Objects;

/**
 * What the data path should do with one message.
 *
 * <p>Besides the {@link TrafficAction}, a decision carries the message to
 * forward. When no interceptor rewrote anything the message is the one that
 * arrived, so the relay writes its original bytes unchanged.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class TrafficDecision {

    private final TrafficAction action;
    private final WireMessage message;

    private TrafficDecision(TrafficAction action, WireMessage message) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
    }

    /** Forward the message, writing {@link WireMessage#outputBytes()}. */
    public static TrafficDecision forward(WireMessage message) {
        return new TrafficDecision(TrafficAction.FORWARD, message);
    }

    /** Deny the message: answer the client with a protocol-native error, then close. */
    public static TrafficDecision deny(WireMessage message) {
        return new TrafficDecision(TrafficAction.DENY, message);
    }

    /** Close the connection immediately, without a protocol response. */
    public static TrafficDecision close(WireMessage message) {
        return new TrafficDecision(TrafficAction.CLOSE, message);
    }

    public TrafficAction action() {
        return action;
    }

    /** The message to forward; carries the rewrite when one happened. */
    public WireMessage message() {
        return message;
    }

    public boolean isForward() {
        return action == TrafficAction.FORWARD;
    }

    /** True when this decision forwards a rewritten payload. */
    public boolean isMutated() {
        return action == TrafficAction.FORWARD && message.mutated();
    }
}
