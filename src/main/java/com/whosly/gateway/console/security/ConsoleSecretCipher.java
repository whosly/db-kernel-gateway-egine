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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AES-GCM envelope encryption for control-plane secrets (instance passwords, masking keys).
 *
 * <p>Stored form: {@code enc:v1:} + Base64({@code iv || ciphertext || tag}).
 * Distinct from result-set {@code MaskingCipher}.</p>
 *
 * <p>Lab default: missing master key allows plaintext writes with a one-time WARN.
 * Production hardening: {@code gateway.console.require-secret-encryption=true} rejects
 * plaintext writes (clear IllegalStateException → HTTP 503) when the key is missing.</p>
 */
public final class ConsoleSecretCipher {

    public static final String PREFIX = "enc:v1:";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(ConsoleSecretCipher.class);

    private final Optional<byte[]> masterKey;
    private final boolean requireSecretEncryption;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicBoolean labModeWarned = new AtomicBoolean(false);
    private final AtomicBoolean legacyPlaintextReadWarned = new AtomicBoolean(false);

    public ConsoleSecretCipher(Optional<byte[]> masterKey) {
        this(masterKey, false);
    }

    public ConsoleSecretCipher(Optional<byte[]> masterKey, boolean requireSecretEncryption) {
        this.masterKey = masterKey != null ? masterKey : Optional.empty();
        this.requireSecretEncryption = requireSecretEncryption;
        this.masterKey.ifPresent(key -> {
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException(
                        "gateway.console.secret-key-base64 must decode to 32 bytes (AES-256), got "
                                + key.length);
            }
        });
        if (requireSecretEncryption && this.masterKey.isEmpty()) {
            log.warn("gateway.console.require-secret-encryption=true 但未配置 secret-key-base64："
                    + "创建/更新实例密码将返回 503，不会明文落库。");
        }
    }

    /** Build from Base64 master key; blank/null → lab mode (no encryption). */
    public static ConsoleSecretCipher fromBase64MasterKey(String base64) {
        return fromBase64MasterKey(base64, false);
    }

    /**
     * @param requireSecretEncryption when true, {@link #sealForStorage} refuses plaintext
     *                                if the master key is missing (production hardening).
     */
    public static ConsoleSecretCipher fromBase64MasterKey(String base64, boolean requireSecretEncryption) {
        if (base64 == null || base64.isBlank()) {
            return new ConsoleSecretCipher(Optional.empty(), requireSecretEncryption);
        }
        byte[] key = Base64.getDecoder().decode(base64.trim());
        return new ConsoleSecretCipher(Optional.of(key), requireSecretEncryption);
    }

    public boolean isMasterKeyConfigured() {
        return masterKey.isPresent();
    }

    public boolean isRequireSecretEncryption() {
        return requireSecretEncryption;
    }

    /** True when a non-empty password write would be sealed (or rejected if require+no key). */
    public boolean allowsPlaintextWrites() {
        return !requireSecretEncryption && masterKey.isEmpty();
    }

    public boolean isEncryptedForm(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * Encrypt plaintext for storage when master key is present.
     * Without master key: lab mode returns plaintext + WARN once;
     * with {@code requireSecretEncryption} throws (no silent plaintext).
     */
    public String sealForStorage(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (isEncryptedForm(plaintext)) {
            return plaintext;
        }
        if (masterKey.isEmpty()) {
            if (requireSecretEncryption) {
                throw new IllegalStateException(
                        "生产加固：gateway.console.require-secret-encryption=true，"
                                + "但未配置有效的 gateway.console.secret-key-base64（32 字节 AES Base64）。"
                                + "拒绝明文写入控制面密码；请配置密钥后重试。");
            }
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
     * Under require mode, legacy plaintext still loads (migrate path) with a one-time WARN.
     */
    public String openFromStorage(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!isEncryptedForm(stored)) {
            if (requireSecretEncryption) {
                warnLegacyPlaintextReadOnce();
            }
            return stored;
        }
        if (masterKey.isEmpty()) {
            throw new IllegalStateException(
                    "控制面存在加密口令，但未配置 gateway.console.secret-key-base64，无法解密");
        }
        String payload = stored.substring(PREFIX.length());
        return decryptRaw(payload);
    }

    /** Non-secret status for console UI / config summary. */
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("masterKeyConfigured", isMasterKeyConfigured());
        body.put("requireSecretEncryption", requireSecretEncryption);
        body.put("allowsPlaintextWrites", allowsPlaintextWrites());
        body.put("storageMode", isMasterKeyConfigured()
                ? "encrypted"
                : (requireSecretEncryption ? "require-encrypted-blocked" : "lab-plaintext"));
        body.put("help", requireSecretEncryption && !isMasterKeyConfigured()
                ? "已强制加密但主密钥缺失：写入密码将 503。配置 gateway.console.secret-key-base64。"
                : (isMasterKeyConfigured()
                ? "控制面密码以 enc:v1: AES-GCM 信封存储。"
                : "实验室模式：密码可明文落 H2（WARN）。生产请配置 secret-key-base64，并设 require-secret-encryption=true。"));
        return body;
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
                    + "生产请配置 32 字节 AES 密钥 Base64，并设 gateway.console.require-secret-encryption=true。");
        }
    }

    private void warnLegacyPlaintextReadOnce() {
        if (legacyPlaintextReadWarned.compareAndSet(false, true)) {
            log.warn("控制面存在遗留明文密码行（require-secret-encryption=true）。"
                    + "读取仍可用；请编辑实例并保存（可重填密码）以迁移为 enc:v1:。新写入已禁止明文。");
        }
    }

    public static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "ConsoleSecretCipher{configured=" + masterKey.isPresent()
                + ", requireEncryption=" + requireSecretEncryption + "}";
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
