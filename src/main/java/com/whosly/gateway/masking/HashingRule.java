package com.whosly.gateway.masking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces the value with a truncated SHA-256 digest.
 *
 * <p>Deterministic, so equal inputs stay equal and masked data can still be
 * grouped or joined. The output is hexadecimal ASCII, which is why the rule is
 * restricted to text columns.</p>
 *
 * <p>The digest protects nothing if values are guessable: an unsalted digest of a
 * phone number is recoverable by enumeration. Supply a {@code salt} kept in a
 * secret manager whenever the masked column is low entropy, and rotate it the
 * same way as any other secret.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class HashingRule extends AbstractMaskingRule {

    private static final int FULL_HEX_LENGTH = 64;

    private final int hexLength;
    private final byte[] salt;

    public HashingRule(String name, int priority, ColumnSelector selector) {
        this(name, priority, selector, 32, "");
    }

    /**
     * @param hexLength number of hexadecimal characters to keep, 1..64
     * @param salt      secret prefix mixed into the digest; empty disables it
     */
    public HashingRule(String name, int priority, ColumnSelector selector, int hexLength, String salt) {
        super(name, priority, selector);
        if (hexLength < 1 || hexLength > FULL_HEX_LENGTH) {
            throw new IllegalArgumentException("hexLength must be between 1 and " + FULL_HEX_LENGTH);
        }
        this.hexLength = hexLength;
        this.salt = Objects.requireNonNull(salt, "salt must not be null").getBytes(StandardCharsets.UTF_8);
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

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new MaskingException("SHA-256 is not available", e);
        }
        digest.update(salt);
        byte[] hash = digest.digest(original.bytes());
        String hex = java.util.HexFormat.of().formatHex(hash);
        return MaskedValue.ofText(hex.substring(0, hexLength), StandardCharsets.US_ASCII);
    }
}
