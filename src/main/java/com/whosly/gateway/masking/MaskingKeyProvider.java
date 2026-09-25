package com.whosly.gateway.masking;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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
     * @param keyId logical key name referenced by a rule / ciphertext
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

    /**
     * Provider backed by multiple raw AES keys (active + previous for rotation).
     *
     * <p>Each entry is cloned; the returned provider clones again on every
     * {@link #key(String)} call.</p>
     */
    static MaskingKeyProvider ofKeys(Map<String, byte[]> keysById) {
        Objects.requireNonNull(keysById, "keysById must not be null");
        if (keysById.isEmpty()) {
            throw new IllegalArgumentException("keysById must not be empty");
        }
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : keysById.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                throw new IllegalArgumentException("key id must not be blank");
            }
            if (e.getValue() == null) {
                throw new IllegalArgumentException("key bytes must not be null for id=" + e.getKey());
            }
            copy.put(e.getKey().trim(), e.getValue().clone());
        }
        Map<String, byte[]> frozen = Map.copyOf(copy);
        return requested -> {
            byte[] key = frozen.get(requested);
            if (key == null) {
                throw new MaskingException("Unknown masking key id: " + requested);
            }
            return key.clone();
        };
    }

    /**
     * Provider backed by multiple Base64-encoded AES keys.
     */
    static MaskingKeyProvider ofBase64Keys(Map<String, String> base64ById) {
        Objects.requireNonNull(base64ById, "base64ById must not be null");
        Map<String, byte[]> raw = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : base64ById.entrySet()) {
            raw.put(e.getKey(), Base64.getDecoder().decode(e.getValue()));
        }
        return ofKeys(raw);
    }
}
