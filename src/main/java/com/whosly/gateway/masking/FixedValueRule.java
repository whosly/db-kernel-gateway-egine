package com.whosly.gateway.masking;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces the value with a constant such as {@code REDACTED}.
 *
 * <p>Text columns only: a constant string is not a valid value for a numeric,
 * temporal or boolean column, and the engine would reject such a rule anyway.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class FixedValueRule extends AbstractMaskingRule {

    private final String fixedValue;
    private final Charset charset;

    public FixedValueRule(String name, int priority, ColumnSelector selector, String fixedValue) {
        this(name, priority, selector, fixedValue, StandardCharsets.UTF_8);
    }

    /**
     * @param charset charset of the connection, used to encode the constant
     */
    public FixedValueRule(String name, int priority, ColumnSelector selector, String fixedValue,
                          Charset charset) {
        super(name, priority, selector);
        this.fixedValue = Objects.requireNonNull(fixedValue, "fixedValue must not be null");
        this.charset = Objects.requireNonNull(charset, "charset must not be null");
    }

    @Override
    public Set<ColumnMetadata.Category> supportedCategories() {
        return Set.of(ColumnMetadata.Category.TEXT);
    }

    @Override
    public boolean matches(ColumnMetadata column) {
        return column.category() == ColumnMetadata.Category.TEXT && targets(column);
    }

    @Override
    public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
        return MaskedValue.ofText(fixedValue, charset);
    }
}
