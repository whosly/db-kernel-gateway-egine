package com.whosly.gateway.masking;

import java.util.Objects;

/**
 * Applies at most one masking rule to one value.
 *
 * <p>One column, one rule: applying several rules in sequence would compound
 * transforms and corrupt the value. Everything the engine does is fail-closed, so
 * a column that should be masked is never forwarded unmasked by accident.</p>
 *
 * <p>Resolve the rule once per column and reuse it for every row of the result
 * set; {@link #apply(ColumnMetadata, MaskedValue)} re-resolves for convenience in
 * tests and low-volume paths.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MaskingEngine {

    private final MaskingRuleRegistry registry;

    public MaskingEngine(MaskingRuleRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** True when this deployment registered rules at all. */
    public boolean isActive() {
        return !registry.isEmpty();
    }

    /**
     * Masks one value.
     *
     * @return the value to forward: the original when no rule matches, otherwise
     *         the masked value
     * @throws MaskingException when masking is required but cannot be applied
     *                          safely
     */
    public MaskedValue apply(ColumnMetadata column, MaskedValue original) {
        Objects.requireNonNull(column, "column must not be null");
        Objects.requireNonNull(original, "original must not be null");

        MaskingRule rule = registry.resolve(column).orElse(null);
        if (rule == null) {
            // Default behaviour: no rule claims this column, so the raw value
            // stays visible.
            return original;
        }
        return apply(rule, column, original);
    }

    /** Masks one value with an already resolved rule. */
    public MaskedValue apply(MaskingRule rule, ColumnMetadata column, MaskedValue original) {
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(column, "column must not be null");
        Objects.requireNonNull(original, "original must not be null");

        if (column.format() == ColumnMetadata.ValueFormat.BINARY) {
            throw new MaskingException("Binary result format is not supported by masking yet: column "
                    + column.name());
        }
        if (!rule.supportedCategories().contains(column.category())) {
            throw new MaskingException("Rule " + rule.name() + " cannot produce a valid "
                    + column.category() + " value for column " + column.name());
        }

        MaskedValue masked;
        try {
            masked = rule.mask(column, original);
        } catch (MaskingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MaskingException("Masking rule " + rule.name()
                    + " failed on column " + column.name(), e);
        }
        if (masked == null) {
            throw new MaskingException("Masking rule " + rule.name()
                    + " returned no value for column " + column.name());
        }
        return masked;
    }
}
