package com.whosly.gateway.adapter.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link DatabaseRiskPolicy} that denies observed traffic matching configured
 * operation names and/or statement keyword substrings.
 *
 * <p>Comparisons are case-insensitive. An empty configuration returns
 * {@link DatabaseRiskPolicy#allowAll()} so deployments keep the historical
 * default until they opt into deny rules.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-09-24
 */
public final class DenyListDatabaseRiskPolicy implements DatabaseRiskPolicy {

    private final List<String> deniedOperations;
    private final List<String> deniedStatementKeywords;

    private DenyListDatabaseRiskPolicy(List<String> deniedOperations, List<String> deniedStatementKeywords) {
        this.deniedOperations = deniedOperations;
        this.deniedStatementKeywords = deniedStatementKeywords;
    }

    /**
     * Builds a policy from configured deny lists.
     *
     * @return {@link DatabaseRiskPolicy#allowAll()} when both lists are empty
     */
    public static DatabaseRiskPolicy of(Collection<String> deniedOperations,
                                        Collection<String> deniedStatementKeywords) {
        List<String> operations = normalize(deniedOperations);
        List<String> keywords = normalize(deniedStatementKeywords);
        if (operations.isEmpty() && keywords.isEmpty()) {
            return DatabaseRiskPolicy.allowAll();
        }
        return new DenyListDatabaseRiskPolicy(operations, keywords);
    }

    private static List<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    @Override
    public RiskDecision evaluate(DatabaseTrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String operation = event.getOperation() == null ? "" : event.getOperation().toLowerCase(Locale.ROOT);
        for (String denied : deniedOperations) {
            if (operation.equals(denied)) {
                return RiskDecision.deny("operation denied by gateway.risk.denied-operations: " + event.getOperation());
            }
        }
        String statement = event.getStatement() == null ? "" : event.getStatement().toLowerCase(Locale.ROOT);
        for (String keyword : deniedStatementKeywords) {
            if (!keyword.isEmpty() && statement.contains(keyword)) {
                return RiskDecision.deny("statement denied by gateway.risk.denied-statement-keywords: " + keyword);
            }
        }
        return RiskDecision.allow();
    }
}
