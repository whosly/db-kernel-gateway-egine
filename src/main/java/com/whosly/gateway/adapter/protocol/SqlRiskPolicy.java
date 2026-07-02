package com.whosly.gateway.adapter.protocol;

/**
 * Decides whether an observed SQL statement can continue to the target database.
 */
@FunctionalInterface
public interface SqlRiskPolicy {

    RiskDecision evaluate(SqlTrafficEvent event);

    static SqlRiskPolicy allowAll() {
        return event -> RiskDecision.allow();
    }
}
