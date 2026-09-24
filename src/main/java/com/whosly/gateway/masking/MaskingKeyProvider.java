package com.whosly.gateway.masking;

import java.util.Base64;
import java.util.Objects;

/**
 * Supplies encryption keys to reversible masking rules.
 *
 * <p>Keys are material: they are returned as a fresh copy, never logged, and
 * never stored with the masked data. A production deployment backs this with a
 * secret manager so keys can be rotated without redeploying rules.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface MaskingKeyProvider {

    /**
     * @param keyId logical key name referenced by a rule
     * @return raw AES key bytes (128, 192 or 256 bits)
     * @throws MaskingException when the key is unknown
     */
    byte[] key(String keyId);

    /**
     * Provider backed by a single Base64-encoded AES key.
     *
     * <p>Intended for tests and for wiring a key that a secret manager resolved at
     * startup; the value must not be committed to the repository.</p>
     */
    static MaskingKeyProvider ofBase64(String keyId, String base64Key) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(base64Key, "base64Key must not be null");
        byte[] key = Base64.getDecoder().decode(base64Key);
        return requested -> {
            if (!keyId.equals(requested)) {
                throw new MaskingException("Unknown masking key id: " + requested);
            }
            return key.clone();
        };
    }
}
