package com.whosly.gateway.adapter.protocol;

/**
 * Decides whether an observed SQL statement can continue to the target database.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface SqlRiskPolicy {

    RiskDecision evaluate(SqlTrafficEvent event);

    static SqlRiskPolicy allowAll() {
        return event -> RiskDecision.allow();
    }
}
