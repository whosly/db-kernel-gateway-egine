package com.whosly.gateway.adapter.protocol;

/**
 * Receives observed database events for audit logging, metrics, or risk analysis.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-03
 */
@FunctionalInterface
public interface DatabaseTrafficObserver {

    void onEvent(DatabaseTrafficEvent event);

    /**
     * Whether losing an event must fail the operation it describes.
     *
     * <p>A recorder that only feeds dashboards is fail-open: a broken sink must not
     * break a client connection. A recorder that is an audit trail is not: an
     * operation the gateway cannot record must not run, or the trail would claim to
     * be complete while missing exactly the operations nobody could write down
     * (rule 8.5).</p>
     *
     * @return {@code true} when delivery failure must deny the operation
     */
    default boolean isDeliveryMandatory() {
        return false;
    }

    /**
     * Signals that a session ended, so a sink can release per-session state.
     *
     * <p>Delivery of an event never creates the state this releases by itself; it
     * exists so long-lived sinks do not accumulate an entry per connection for the
     * life of the process.</p>
     */
    default void onSessionClosed(String sessionId) {
    }

    static DatabaseTrafficObserver noop() {
        return event -> {
        };
    }

    /**
     * Wraps a sink so observed SQL reaches it with literal values masked, while
     * the bytes forwarded to the database stay unchanged (rule 8.2).
     */
    static DatabaseTrafficObserver masking(DatabaseTrafficObserver delegate) {
        return new MaskingTrafficObserver(delegate);
    }
}
