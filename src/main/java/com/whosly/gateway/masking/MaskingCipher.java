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
import java.util.Optional;

/**
 * Authenticated encryption for reversible masking.
 *
 * <p>AES-GCM with a random nonce per value. Wire format (v1):
 * {@code enc:v1:<keyId>:<Base64(nonce || ciphertext+tag)}}. The embedded key id
 * lets decrypt pick the right key during rotation. Legacy bare Base64 payloads
 * (no prefix) are still accepted when an explicit keyId is supplied.</p>
 *
 * <p>Failures never carry key or plaintext material, and keys are fetched per
 * call so rotation takes effect immediately.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MaskingCipher {

    /** Prefixed wire form produced by {@link #encrypt(String, String)}. */
    public static final String WIRE_PREFIX = "enc:v1:";

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
     * @return {@code enc:v1:<keyId>:<Base64(nonce || ciphertext + tag)>}
     */
    public String encrypt(String keyId, String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        String id = requireKeyId(keyId);
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, id, nonce)
                    .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length)
                            .put(nonce)
                            .put(ciphertext)
                            .array());
            return WIRE_PREFIX + id + ":" + payload;
        } catch (GeneralSecurityException e) {
            throw new MaskingException("Masking encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String, String)}.
     *
     * <p>When {@code encoded} embeds a key id ({@code enc:v1:…}), that id is used
     * (rotation-safe). Otherwise the legacy bare-Base64 form is decrypted with
     * {@code keyId}.</p>
     *
     * @throws MaskingException when the value was tampered with or the key does
     *                          not match
     */
    public String decrypt(String keyId, String encoded) {
        Objects.requireNonNull(encoded, "encoded must not be null");
        Parsed parsed = parse(encoded);
        String useKeyId = parsed.keyId().orElse(null);
        if (useKeyId == null) {
            useKeyId = requireKeyId(keyId);
        }
        return decryptPayload(useKeyId, parsed.payload());
    }

    /**
     * Decrypts a prefixed payload using the embedded key id.
     *
     * @throws MaskingException when the value lacks an embedded key id or fails auth
     */
    public String decrypt(String encoded) {
        Objects.requireNonNull(encoded, "encoded must not be null");
        Parsed parsed = parse(encoded);
        String useKeyId = parsed.keyId().orElseThrow(() ->
                new MaskingException("Masked value has no embedded key id; pass keyId explicitly"));
        return decryptPayload(useKeyId, parsed.payload());
    }

    /**
     * @return embedded key id when {@code encoded} is {@code enc:v1:…}; empty for legacy bare Base64
     */
    public static Optional<String> extractKeyId(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        return parse(encoded).keyId();
    }

    /**
     * @return {@code true} when value uses the {@code enc:v1:} wire prefix
     */
    public static boolean isPrefixed(String encoded) {
        return encoded != null && encoded.startsWith(WIRE_PREFIX);
    }

    private String decryptPayload(String keyId, String base64Payload) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Payload);
            if (decoded.length <= NONCE_LENGTH) {
                throw new MaskingException("Masked value is too short to be authentic");
            }
            byte[] nonce = Arrays.copyOfRange(decoded, 0, NONCE_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(decoded, NONCE_LENGTH, decoded.length);
            return new String(cipher(Cipher.DECRYPT_MODE, keyId, nonce).doFinal(ciphertext),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new MaskingException("Masked value is not valid Base64", e);
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

    private static String requireKeyId(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new MaskingException("Masking key id must not be blank");
        }
        String id = keyId.trim();
        if (id.indexOf(':') >= 0) {
            throw new MaskingException("Masking key id must not contain ':'");
        }
        return id;
    }

    private static Parsed parse(String encoded) {
        if (encoded.startsWith(WIRE_PREFIX)) {
            String rest = encoded.substring(WIRE_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon <= 0 || colon >= rest.length() - 1) {
                throw new MaskingException("Masked value has invalid enc:v1: layout");
            }
            String keyId = rest.substring(0, colon);
            String payload = rest.substring(colon + 1);
            if (keyId.isBlank() || keyId.indexOf(':') >= 0) {
                throw new MaskingException("Masked value has invalid embedded key id");
            }
            return new Parsed(Optional.of(keyId), payload);
        }
        return new Parsed(Optional.empty(), encoded);
    }

    private record Parsed(Optional<String> keyId, String payload) {
    }
}
