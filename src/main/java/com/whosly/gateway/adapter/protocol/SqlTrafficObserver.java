package com.whosly.gateway.adapter.protocol;

/**
 * Receives SQL events for audit logging, persistence, metrics, or downstream risk analysis.
 */
@FunctionalInterface
public interface SqlTrafficObserver {

    void onSql(SqlTrafficEvent event);

    static SqlTrafficObserver noop() {
        return event -> {
        };
    }
}
