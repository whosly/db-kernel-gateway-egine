package com.whosly.gateway.console.masking;

import com.whosly.gateway.console.persist.MaskingRuleRecord;
import com.whosly.gateway.console.security.ConsoleMaskingKeyHolder;
import com.whosly.gateway.masking.ColumnSelector;
import com.whosly.gateway.masking.EncryptingRule;
import com.whosly.gateway.masking.FixedValueRule;
import com.whosly.gateway.masking.HashingRule;
import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.NullingRule;
import com.whosly.gateway.masking.PartialMaskRule;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps a persisted H2 masking-rule row to a runtime {@link MaskingRule}.
 *
 * <p>Instance rules receive a priority boost so they win over process-level
 * Spring {@code MaskingRule} beans targeting the same column (CONSOLE_ARCHITECTURE §11.4).</p>
 *
 * <p>Cipher/keyId come from {@link ConsoleMaskingKeyHolder} when provided (hot-reloadable);
 * otherwise from constructor snapshot (tests).</p>
 */
public final class InstanceMaskingRuleCompiler {

    /** Added to stored priority so instance H2 rules beat global beans. */
    public static final int INSTANCE_PRIORITY_BOOST = 1_000_000;

    private final ConsoleMaskingKeyHolder holder; // nullable
    private volatile Optional<MaskingCipher> cipher;
    private volatile String keyId;

    public InstanceMaskingRuleCompiler(MaskingCipher cipher, String keyId) {
        this.holder = null;
        this.cipher = Optional.ofNullable(cipher);
        this.keyId = keyId != null && !keyId.isBlank() ? keyId : "default";
    }

    public InstanceMaskingRuleCompiler(Optional<MaskingCipher> cipher, String keyId) {
        this.holder = null;
        this.cipher = cipher != null ? cipher : Optional.empty();
        this.keyId = keyId != null && !keyId.isBlank() ? keyId : "default";
    }

    public InstanceMaskingRuleCompiler(ConsoleMaskingKeyHolder holder) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.cipher = holder.cipher();
        this.keyId = holder.keyId();
    }

    public boolean encryptAvailable() {
        return resolveCipher().isPresent();
    }

    public String keyId() {
        return resolveKeyId();
    }

    public void reconfigure(MaskingCipher newCipher, String newKeyId) {
        this.cipher = Optional.ofNullable(newCipher);
        this.keyId = newKeyId != null && !newKeyId.isBlank() ? newKeyId : "default";
    }

    private Optional<MaskingCipher> resolveCipher() {
        if (holder != null) {
            return holder.cipher();
        }
        return cipher;
    }

    private String resolveKeyId() {
        if (holder != null) {
            return holder.keyId();
        }
        return keyId;
    }

    /**
     * Validates API/input fields before persist. Throws {@link IllegalArgumentException}
     * with a clear Chinese/English message suitable for HTTP 400.
     */
    public void validateForPersist(MaskingRuleRecord row) {
        Objects.requireNonNull(row, "row");
        if (!hasText(row.name())) {
            throw new IllegalArgumentException("name is required");
        }
        String strategy = normalizeStrategy(row.strategy());
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "strategy must be one of: null, fixed, partial, hash, encrypt");
        }
        ColumnSelector selector = buildSelector(row);
        if (selector == null) {
            throw new IllegalArgumentException(
                    "columnName 或 namePattern 至少填一个（可选 tableName）");
        }
        switch (strategy) {
            case "fixed" -> {
                if (row.fixedValue() == null) {
                    throw new IllegalArgumentException("fixed strategy requires fixedValue");
                }
            }
            case "partial" -> {
                int prefix = row.keepPrefix() != null ? row.keepPrefix() : 0;
                int suffix = row.keepSuffix() != null ? row.keepSuffix() : 0;
                if (prefix < 0 || suffix < 0) {
                    throw new IllegalArgumentException("keepPrefix/keepSuffix must not be negative");
                }
            }
            case "hash" -> {
                int hex = row.hashHexLength() != null ? row.hashHexLength() : 32;
                if (hex < 1 || hex > 64) {
                    throw new IllegalArgumentException("hashHexLength must be between 1 and 64");
                }
            }
            case "encrypt" -> {
                if (resolveCipher().isEmpty()) {
                    throw new IllegalArgumentException(
                            "encrypt strategy requires a masking key："
                                    + "设置 gateway.masking.key-base64 或在管控台「安全」中配置脱敏密钥");
                }
            }
            case "null" -> {
                // no extra fields
            }
            default -> throw new IllegalArgumentException("Unsupported strategy: " + strategy);
        }
    }

    public MaskingRule compile(MaskingRuleRecord row) {
        Objects.requireNonNull(row, "row");
        validateForPersist(row);
        String strategy = normalizeStrategy(row.strategy());
        ColumnSelector selector = buildSelector(row);
        int priority = row.priority() + INSTANCE_PRIORITY_BOOST;
        String runtimeName = "inst:" + row.instanceId() + ":" + row.id();
        Optional<MaskingCipher> activeCipher = resolveCipher();
        String activeKeyId = resolveKeyId();

        return switch (strategy) {
            case "null" -> new NullingRule(runtimeName, priority, selector);
            case "fixed" -> new FixedValueRule(runtimeName, priority, selector, row.fixedValue());
            case "partial" -> new PartialMaskRule(
                    runtimeName,
                    priority,
                    selector,
                    row.keepPrefix() != null ? row.keepPrefix() : 0,
                    row.keepSuffix() != null ? row.keepSuffix() : 0);
            case "hash" -> new HashingRule(
                    runtimeName,
                    priority,
                    selector,
                    row.hashHexLength() != null ? row.hashHexLength() : 32,
                    "");
            case "encrypt" -> new EncryptingRule(
                    runtimeName,
                    priority,
                    selector,
                    activeCipher.orElseThrow(),
                    activeKeyId);
            default -> throw new IllegalArgumentException("Unsupported strategy: " + strategy);
        };
    }

    static ColumnSelector buildSelector(MaskingRuleRecord row) {
        ColumnSelector byColumn = null;
        if (hasText(row.columnName())) {
            byColumn = ColumnSelector.named(row.columnName().trim());
        } else if (hasText(row.namePattern())) {
            byColumn = ColumnSelector.namePattern(row.namePattern().trim());
        }
        if (byColumn == null) {
            return null;
        }
        if (hasText(row.tableName())) {
            return byColumn.and(ColumnSelector.inTable(row.tableName().trim()));
        }
        return byColumn;
    }

    static String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return null;
        }
        String s = strategy.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "null", "nulling", "empty" -> "null";
            case "fixed", "fixedvalue", "fixed_value" -> "fixed";
            case "partial", "mask", "partial_mask" -> "partial";
            case "hash", "hashing" -> "hash";
            case "encrypt", "encryption", "cipher" -> "encrypt";
            default -> null;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
