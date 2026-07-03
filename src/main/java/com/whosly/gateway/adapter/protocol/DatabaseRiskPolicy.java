package com.whosly.gateway.adapter.protocol;

/**
 * Decides whether an observed database operation can continue to the target database.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-03
 */
@FunctionalInterface
public interface DatabaseRiskPolicy {

    RiskDecision evaluate(DatabaseTrafficEvent event);

    static DatabaseRiskPolicy allowAll() {
        return event -> RiskDecision.allow();
    }
}
