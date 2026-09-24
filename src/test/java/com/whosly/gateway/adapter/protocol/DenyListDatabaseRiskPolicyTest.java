package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DenyListDatabaseRiskPolicyTest {

    @Test
    void emptyConfigurationReturnsAllowAllBehaviour() {
        DatabaseRiskPolicy policy = DenyListDatabaseRiskPolicy.of(List.of(), List.of());

        RiskDecision decision = policy.evaluate(event("COM_QUERY", "drop table accounts"));

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void deniesMatchingOperationsCaseInsensitively() {
        DatabaseRiskPolicy policy = DenyListDatabaseRiskPolicy.of(
                List.of("COM_PROCESS_KILL", " drop "), List.of());

        assertThat(policy.evaluate(event("com_process_kill", "KILL 1")).isAllowed()).isFalse();
        assertThat(policy.evaluate(event("COM_QUERY", "select 1")).isAllowed()).isTrue();
    }

    @Test
    void deniesStatementKeywordSubstringsCaseInsensitively() {
        DatabaseRiskPolicy policy = DenyListDatabaseRiskPolicy.of(
                List.of(), List.of("drop table", "TRUNCATE"));

        RiskDecision denied = policy.evaluate(event("COM_QUERY", "Drop Table accounts"));
        RiskDecision allowed = policy.evaluate(event("COM_QUERY", "select 1 from accounts"));

        assertThat(denied.isAllowed()).isFalse();
        assertThat(denied.getReason()).contains("denied-statement-keywords");
        assertThat(allowed.isAllowed()).isTrue();
    }

    @Test
    void ignoresBlankEntriesInConfiguredLists() {
        DatabaseRiskPolicy policy = DenyListDatabaseRiskPolicy.of(
                List.of(" ", ""), List.of("  "));

        // Blank-only input collapses to allow-all.
        assertThat(policy.evaluate(event("COM_QUERY", "drop table x")).isAllowed()).isTrue();
    }

    private static DatabaseTrafficEvent event(String operation, String statement) {
        return DatabaseTrafficEvent.builder("MySQL", "risk-session", operation, statement).build();
    }
}
