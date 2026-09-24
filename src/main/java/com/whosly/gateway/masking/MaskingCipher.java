package com.whosly.gateway.masking;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Authenticated encryption for reversible masking.
 *
 * <p>AES-GCM with a random nonce per value: masking is deterministic in shape but
 * not in bytes, so two equal plaintexts do not produce equal ciphertexts. The
 * nonce travels with the ciphertext as {@code Base64(nonce || ciphertext+tag)}.</p>
 *
 * <p>Failures never carry key or plaintext material, and keys are fetched per
 * call so rotation takes effect immediately.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MaskingCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final MaskingKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public MaskingCipher(MaskingKeyProvider keyProvider) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider must not be null");
    }

    /**
     * Encrypts one value.
     *
     * @return Base64 of {@code nonce || ciphertext + tag}
     */
    public String encrypt(String keyId, String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, keyId, nonce)
                    .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length)
                            .put(nonce)
                            .put(ciphertext)
                            .array());
        } catch (GeneralSecurityException e) {
            throw new MaskingException("Masking encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String, String)}.
     *
     * @throws MaskingException when the value was tampered with or the key does
     *                          not match
     */
    public String decrypt(String keyId, String encoded) {
        Objects.requireNonNull(encoded, "encoded must not be null");
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length <= NONCE_LENGTH) {
                throw new MaskingException("Masked value is too short to be authentic");
            }
            byte[] nonce = Arrays.copyOfRange(decoded, 0, NONCE_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(decoded, NONCE_LENGTH, decoded.length);
            return new String(cipher(Cipher.DECRYPT_MODE, keyId, nonce).doFinal(ciphertext),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new MaskingException("Masking decryption failed", e);
        }
    }

    private Cipher cipher(int mode, String keyId, byte[] nonce) throws GeneralSecurityException {
        byte[] keyBytes = keyProvider.key(keyId);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new MaskingException("Masking key must be 128, 192 or 256 bits");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        return cipher;
    }
}
