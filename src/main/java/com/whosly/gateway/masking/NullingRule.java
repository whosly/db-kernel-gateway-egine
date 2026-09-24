package com.whosly.gateway.masking;

import java.util.EnumSet;
import java.util.Set;

/**
 * Replaces the value with NULL.
 *
 * <p>The safest default masking: it produces a value that is valid for every
 * category, at the cost of removing the value entirely. It only applies to
 * columns that accept NULL, because writing NULL into a NOT NULL column would
 * produce a result the client did not expect from the schema.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class NullingRule extends AbstractMaskingRule {

    public NullingRule(String name, int priority, ColumnSelector selector) {
        super(name, priority, selector);
    }

    @Override
    public Set<ColumnMetadata.Category> supportedCategories() {
        return EnumSet.allOf(ColumnMetadata.Category.class);
    }

    @Override
    public boolean matches(ColumnMetadata column) {
        return column.nullable() && targets(column);
    }

    @Override
    public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
        return MaskedValue.ofNull();
    }
}
