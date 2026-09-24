package com.whosly.gateway.console.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AES-GCM envelope encryption for control-plane secrets (instance passwords, masking keys).
 *
 * <p>Stored form: {@code enc:v1:} + Base64({@code iv || ciphertext || tag}).
 * Distinct from result-set {@code MaskingCipher}.</p>
 */
public final class ConsoleSecretCipher {

    public static final String PREFIX = "enc:v1:";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(ConsoleSecretCipher.class);

    private final Optional<byte[]> masterKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicBoolean labModeWarned = new AtomicBoolean(false);

    public ConsoleSecretCipher(Optional<byte[]> masterKey) {
        this.masterKey = masterKey != null ? masterKey : Optional.empty();
        this.masterKey.ifPresent(key -> {
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException(
                        "gateway.console.secret-key-base64 must decode to 32 bytes (AES-256), got "
                                + key.length);
            }
        });
    }

    /** Build from Base64 master key; blank/null → lab mode (no encryption). */
    public static ConsoleSecretCipher fromBase64MasterKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return new ConsoleSecretCipher(Optional.empty());
        }
        byte[] key = Base64.getDecoder().decode(base64.trim());
        return new ConsoleSecretCipher(Optional.of(key));
    }

    public boolean isMasterKeyConfigured() {
        return masterKey.isPresent();
    }

    public boolean isEncryptedForm(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * Encrypt plaintext for storage when master key is present.
     * Without master key: returns plaintext and logs WARN once (lab mode).
     */
    public String sealForStorage(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (isEncryptedForm(plaintext)) {
            return plaintext;
        }
        if (masterKey.isEmpty()) {
            warnLabModeOnce();
            return plaintext;
        }
        return PREFIX + encryptRaw(plaintext);
    }

    /**
     * Require master key and encrypt; throws if key missing (for masking-key PUT etc.).
     */
    public String requireSeal(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is required");
        }
        if (masterKey.isEmpty()) {
            throw new IllegalStateException(
                    "需要配置 gateway.console.secret-key-base64（32 字节 AES 密钥 Base64）才能加密存储控制面密钥");
        }
        if (isEncryptedForm(plaintext)) {
            return plaintext;
        }
        return PREFIX + encryptRaw(plaintext);
    }

    /**
     * Decrypt if prefixed; otherwise treat as legacy plaintext.
     */
    public String openFromStorage(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!isEncryptedForm(stored)) {
            return stored;
        }
        if (masterKey.isEmpty()) {
            throw new IllegalStateException(
                    "控制面存在加密口令，但未配置 gateway.console.secret-key-base64，无法解密");
        }
        String payload = stored.substring(PREFIX.length());
        return decryptRaw(payload);
    }

    private String encryptRaw(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey.orElseThrow(), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Control-plane encryption failed", e);
        }
    }

    private String decryptRaw(String base64Packed) {
        try {
            byte[] packed = Base64.getDecoder().decode(base64Packed);
            if (packed.length <= IV_LENGTH) {
                throw new IllegalStateException("Encrypted value too short");
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey.orElseThrow(), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Control-plane decryption failed", e);
        }
    }

    private void warnLabModeOnce() {
        if (labModeWarned.compareAndSet(false, true)) {
            log.warn("gateway.console.secret-key-base64 未配置：控制面密码将以明文写入 H2（实验室模式）。"
                    + "生产请配置 32 字节 AES 密钥 Base64。");
        }
    }

    public static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "ConsoleSecretCipher{configured=" + masterKey.isPresent() + "}";
    }

    public static byte[] decodeAesKey(String base64) {
        Objects.requireNonNull(base64, "base64");
        byte[] key = Base64.getDecoder().decode(base64.trim());
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            wipe(key);
            throw new IllegalArgumentException("AES key must be 128/192/256 bits (Base64)");
        }
        return key;
    }
}
