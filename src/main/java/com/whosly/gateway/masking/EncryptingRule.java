package com.whosly.gateway.masking;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces the value with an AES-GCM ciphertext, Base64 encoded.
 *
 * <p>Reversible masking: an operator holding the key can recover the value with
 * {@link MaskingCipher#decrypt(String, String)}. Each value gets a random nonce,
 * so equal plaintexts produce different ciphertexts, which is the right choice
 * when the masked data must not be comparable.</p>
 *
 * <p>The ciphertext is longer than the plaintext, so the column must be wide
 * enough to carry it; a declared column width is not enforced by the protocol,
 * but a client that truncates on display will show a partial ciphertext. Only use
 * this rule on columns whose consumers tolerate that.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class EncryptingRule extends AbstractMaskingRule {

    private final MaskingCipher cipher;
    private final String keyId;
    private final Charset charset;

    public EncryptingRule(String name, int priority, ColumnSelector selector,
                          MaskingCipher cipher, String keyId) {
        this(name, priority, selector, cipher, keyId, StandardCharsets.UTF_8);
    }

    /**
     * @param keyId   key name resolved through the cipher's key provider
     * @param charset charset of the connection, used to read the plaintext
     */
    public EncryptingRule(String name, int priority, ColumnSelector selector,
                          MaskingCipher cipher, String keyId, Charset charset) {
        super(name, priority, selector);
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
        this.keyId = Objects.requireNonNull(keyId, "keyId must not be null");
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
        return MaskedValue.ofText(cipher.encrypt(keyId, original.asText(charset)), StandardCharsets.US_ASCII);
    }
}
