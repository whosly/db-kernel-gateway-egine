package com.whosly.gateway.adapter.protocol;

/**
 * Receives SQL events for audit logging, persistence, metrics, or downstream risk analysis.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface SqlTrafficObserver {

    void onSql(SqlTrafficEvent event);

    static SqlTrafficObserver noop() {
        return event -> {
        };
    }
}
