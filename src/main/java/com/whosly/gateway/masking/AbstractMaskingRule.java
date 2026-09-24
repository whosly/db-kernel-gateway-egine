package com.whosly.gateway.masking;

import java.util.Objects;

/**
 * Shared identity and targeting for the built-in rules.
 *
 * <p>Custom rules implement {@link MaskingRule} directly; this base only removes
 * boilerplate from the rules shipped with the gateway.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
abstract class AbstractMaskingRule implements MaskingRule {

    private final String name;
    private final int priority;
    private final ColumnSelector selector;

    AbstractMaskingRule(String name, int priority, ColumnSelector selector) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.priority = priority;
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    /** True when the deployment's selector targets this column. */
    protected boolean targets(ColumnMetadata column) {
        return selector.matches(column);
    }
}
