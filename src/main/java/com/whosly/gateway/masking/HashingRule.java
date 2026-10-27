package com.whosly.gateway.masking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Replaces the value with a truncated SHA-256 digest, or a deterministic uuid
 * derived from that digest.
 *
 * <p>Deterministic, so equal inputs stay equal and masked data can still be
 * grouped or joined. Text columns receive hexadecimal ASCII; uuid columns
 * receive a canonical uuid string built from the first 16 digest bytes so the
 * client's type model still accepts the value.</p>
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

    private static final Set<ColumnMetadata.Category> SUPPORTED = EnumSet.of(
            ColumnMetadata.Category.TEXT,
            ColumnMetadata.Category.UUID,
            // OTHER: SQL Server TVP wholesale — interceptor broadcasts into nested cells.
            ColumnMetadata.Category.OTHER);

    private final int hexLength;
    private final byte[] salt;

    public HashingRule(String name, int priority, ColumnSelector selector) {
        this(name, priority, selector, 32, "");
    }

    /**
     * @param hexLength number of hexadecimal characters to keep for text columns, 1..64
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

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new MaskingException("SHA-256 is not available", e);
        }
        digest.update(salt);
        byte[] hash = digest.digest(original.bytes());
        if (column.category() == ColumnMetadata.Category.UUID) {
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
            String uuid = new UUID(buffer.getLong(), buffer.getLong()).toString();
            return MaskedValue.ofText(uuid, StandardCharsets.US_ASCII);
        }
        String hex = java.util.HexFormat.of().formatHex(hash);
        return MaskedValue.ofText(hex.substring(0, hexLength), StandardCharsets.US_ASCII);
    }
}
