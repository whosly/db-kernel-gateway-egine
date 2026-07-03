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
}
