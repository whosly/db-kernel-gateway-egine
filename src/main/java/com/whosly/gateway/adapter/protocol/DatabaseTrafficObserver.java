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
