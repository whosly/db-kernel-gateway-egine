package com.whosly.gateway.masking;

import java.util.Set;

/**
 * One masking rule.
 *
 * <p>Rules are the injection point of the masking engine: a deployment registers
 * the rules it wants, and the engine applies at most one of them per column.
 * Rules are ordered by {@link #priority()} and must be free of hidden state, so
 * the same column always resolves to the same rule.</p>
 *
 * <p>Unlike an optimizer's rule set, masking rules are applied once rather than
 * to a fixed point: applying several rules in sequence to one value would
 * compound transforms and corrupt the data.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public interface MaskingRule {

    /** Stable rule name; unique within a registry and used in logs and metrics. */
    String name();

    /**
     * Higher values win when several rules match the same column.
     *
     * <p>Two rules sharing the highest priority for one column are a
     * configuration conflict and are rejected at resolution time.</p>
     */
    default int priority() {
        return 0;
    }

    /**
     * Categories this rule can produce valid values for.
     *
     * <p>Declaring the contract lets the engine refuse a rule that would write a
     * value the client cannot parse, instead of corrupting the result set.</p>
     */
    Set<ColumnMetadata.Category> supportedCategories();

    /** Whether this rule applies to the column. */
    boolean matches(ColumnMetadata column);

    /**
     * Produces the replacement value.
     *
     * @param column   the column being masked
     * @param original the value as it arrived; rules that ignore it still receive
     *                 it so they can mask partially
     */
    MaskedValue mask(ColumnMetadata column, MaskedValue original);
}
