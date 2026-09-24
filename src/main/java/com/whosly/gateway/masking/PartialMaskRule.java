package com.whosly.gateway.masking;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps the edges of a value and masks the middle, for example
 * {@code 6222********1234}.
 *
 * <p>Useful when part of a value must stay recognisable, such as the last digits
 * of a card number. Masking is measured in characters, not bytes, so multi-byte
 * text keeps its shape; the field length is recomputed by the writer.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class PartialMaskRule extends AbstractMaskingRule {

    private final int keepPrefix;
    private final int keepSuffix;
    private final char maskCharacter;
    private final Charset charset;

    public PartialMaskRule(String name, int priority, ColumnSelector selector,
                           int keepPrefix, int keepSuffix) {
        this(name, priority, selector, keepPrefix, keepSuffix, '*', StandardCharsets.UTF_8);
    }

    public PartialMaskRule(String name, int priority, ColumnSelector selector,
                           int keepPrefix, int keepSuffix, char maskCharacter, Charset charset) {
        super(name, priority, selector);
        if (keepPrefix < 0 || keepSuffix < 0) {
            throw new IllegalArgumentException("kept characters must not be negative");
        }
        this.keepPrefix = keepPrefix;
        this.keepSuffix = keepSuffix;
        this.maskCharacter = maskCharacter;
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
        if (original.isNull()) {
            return original;
        }

        String text = original.asText(charset);
        if (text.length() <= keepPrefix + keepSuffix) {
            // Everything would be kept, so mask the whole value instead.
            return MaskedValue.ofText(repeat(Math.max(text.length(), 1)), charset);
        }
        String masked = text.substring(0, keepPrefix)
                + repeat(text.length() - keepPrefix - keepSuffix)
                + text.substring(text.length() - keepSuffix);
        return MaskedValue.ofText(masked, charset);
    }

    private String repeat(int count) {
        return String.valueOf(maskCharacter).repeat(count);
    }
}
