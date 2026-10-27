package com.whosly.gateway.masking;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces the value with a constant such as {@code REDACTED} or {@code 0}.
 *
 * <p>Claims text, numeric, temporal, uuid, binary, and {@code OTHER} (SQL Server
 * TVP wholesale broadcast). The constant must be a value the column's wire
 * encoder can represent (for example {@code 0} for a numeric column,
 * {@code 1970-01-01} for a date, a canonical uuid string for uuid). Invalid
 * constants fail closed when the protocol layer encodes them. On a TVP column the
 * interceptor broadcasts this constant into every nested cell.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class FixedValueRule extends AbstractMaskingRule {

    private static final Set<ColumnMetadata.Category> SUPPORTED = EnumSet.of(
            ColumnMetadata.Category.TEXT,
            ColumnMetadata.Category.NUMERIC,
            ColumnMetadata.Category.TEMPORAL,
            ColumnMetadata.Category.UUID,
            ColumnMetadata.Category.BINARY,
            ColumnMetadata.Category.OTHER);

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
        return SUPPORTED;
    }

    @Override
    public boolean matches(ColumnMetadata column) {
        return SUPPORTED.contains(column.category()) && targets(column);
    }

    @Override
    public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
        return MaskedValue.ofText(fixedValue, charset);
    }
}
