package com.whosly.gateway.masking;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
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
 * <p>Also claims uuid columns: hex digits are masked while the canonical
 * {@code 8-4-4-4-12} hyphen layout is preserved so the client still parses a
 * uuid.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class PartialMaskRule extends AbstractMaskingRule {

    private static final Set<ColumnMetadata.Category> SUPPORTED = EnumSet.of(
            ColumnMetadata.Category.TEXT,
            ColumnMetadata.Category.UUID,
            // OTHER: SQL Server TVP wholesale — interceptor broadcasts into nested cells.
            ColumnMetadata.Category.OTHER);

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
        return SUPPORTED;
    }

    @Override
    public boolean matches(ColumnMetadata column) {
        return SUPPORTED.contains(column.category()) && targets(column);
    }

    @Override
    public MaskedValue mask(ColumnMetadata column, MaskedValue original) {
        if (original.isNull()) {
            return original;
        }

        if (column.category() == ColumnMetadata.Category.UUID) {
            return MaskedValue.ofText(maskUuid(original.asText(charset)), charset);
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

    /**
     * Masks only hex digits with {@code 0} (not {@code *}), then re-inserts hyphens
     * at the canonical offsets so the result remains a parseable uuid for binary
     * re-encoding. The configured mask character is for text columns only.
     */
    private String maskUuid(String raw) {
        String hex = raw.trim().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (hex.length() != 32 || !hex.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new MaskingException("Partial mask requires a canonical uuid value");
        }
        String maskedHex = maskPlain(hex, '0');
        return maskedHex.substring(0, 8) + "-"
                + maskedHex.substring(8, 12) + "-"
                + maskedHex.substring(12, 16) + "-"
                + maskedHex.substring(16, 20) + "-"
                + maskedHex.substring(20);
    }

    private String maskPlain(String text) {
        return maskPlain(text, maskCharacter);
    }

    private String maskPlain(String text, char fill) {
        if (text.length() <= keepPrefix + keepSuffix) {
            return String.valueOf(fill).repeat(Math.max(text.length(), 1));
        }
        return text.substring(0, keepPrefix)
                + String.valueOf(fill).repeat(text.length() - keepPrefix - keepSuffix)
                + text.substring(text.length() - keepSuffix);
    }

    private String repeat(int count) {
        return String.valueOf(maskCharacter).repeat(count);
    }
}
