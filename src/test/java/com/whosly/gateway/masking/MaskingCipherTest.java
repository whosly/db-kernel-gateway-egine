package com.whosly.gateway.masking;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaskingCipherTest {

    private static final String KEY_ID = "k1";

    @Test
    void encryptsAndDecryptsWithTheSameKey() {
        MaskingCipher cipher = new MaskingCipher(randomKey(KEY_ID, 32));

        String encrypted = cipher.encrypt(KEY_ID, "13800000000");

        assertThat(encrypted).startsWith(MaskingCipher.WIRE_PREFIX + KEY_ID + ":");
        assertThat(encrypted).isNotEqualTo("13800000000");
        assertThat(cipher.decrypt(KEY_ID, encrypted)).isEqualTo("13800000000");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("13800000000");
        assertThat(MaskingCipher.extractKeyId(encrypted)).contains(KEY_ID);
    }

    @Test
    void producesADifferentCiphertextForEveryCall() {
        MaskingCipher cipher = new MaskingCipher(randomKey(KEY_ID, 32));

        assertThat(cipher.encrypt(KEY_ID, "same")).isNotEqualTo(cipher.encrypt(KEY_ID, "same"));
    }

    @Test
    void refusesToDecryptWithAnotherKey() {
        String encrypted = new MaskingCipher(randomKey(KEY_ID, 32)).encrypt(KEY_ID, "secret");
        MaskingCipher other = new MaskingCipher(randomKey("k2", 32));

        // Prefixed payload embeds k1 — other provider cannot resolve k1
        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("Unknown masking key id");
    }

    @Test
    void decryptsLegacyBareBase64WithExplicitKeyId() {
        MaskingCipher cipher = new MaskingCipher(randomKey(KEY_ID, 32));
        // Build legacy payload by stripping the new prefix
        String prefixed = cipher.encrypt(KEY_ID, "legacy-plain");
        String bare = prefixed.substring((MaskingCipher.WIRE_PREFIX + KEY_ID + ":").length());
        assertThat(MaskingCipher.isPrefixed(bare)).isFalse();
        assertThat(cipher.decrypt(KEY_ID, bare)).isEqualTo("legacy-plain");
    }

    @Test
    void rotationDecryptsWithPreviousKeyViaEmbeddedKeyId() {
        byte[] k1 = randomBytes(32);
        byte[] k2 = randomBytes(32);
        Map<String, byte[]> both = new LinkedHashMap<>();
        both.put("k1", k1);
        both.put("k2", k2);
        MaskingCipher onlyK1 = new MaskingCipher(MaskingKeyProvider.ofKeys(Map.of("k1", k1)));
        String underK1 = onlyK1.encrypt("k1", "rotate-me");

        MaskingCipher ring = new MaskingCipher(MaskingKeyProvider.ofKeys(both));
        assertThat(ring.decrypt(underK1)).isEqualTo("rotate-me");
        String underK2 = ring.encrypt("k2", "new-active");
        assertThat(MaskingCipher.extractKeyId(underK2)).contains("k2");
        assertThat(ring.decrypt(underK2)).isEqualTo("new-active");
    }

    @Test
    void refusesTamperedCiphertext() {
        MaskingCipher cipher = new MaskingCipher(randomKey(KEY_ID, 32));
        String encrypted = cipher.encrypt(KEY_ID, "secret");
        String payload = encrypted.substring((MaskingCipher.WIRE_PREFIX + KEY_ID + ":").length());
        byte[] raw = Base64.getDecoder().decode(payload);
        raw[raw.length - 1] ^= 0x01;
        String tampered = MaskingCipher.WIRE_PREFIX + KEY_ID + ":" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(KEY_ID, tampered))
                .isInstanceOf(MaskingException.class);
    }

    @Test
    void rejectsAKeyOfTheWrongLength() {
        MaskingCipher cipher = new MaskingCipher(randomKey(KEY_ID, 8));

        assertThatThrownBy(() -> cipher.encrypt(KEY_ID, "x"))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("128, 192 or 256 bits");
    }

    @Test
    void rejectsAnUnknownKeyId() {
        MaskingCipher cipher = new MaskingCipher(randomKey(KEY_ID, 32));

        assertThatThrownBy(() -> cipher.encrypt("missing", "x"))
                .isInstanceOf(MaskingException.class)
                .hasMessageContaining("Unknown masking key id");
    }

    private static MaskingKeyProvider randomKey(String keyId, int size) {
        return MaskingKeyProvider.ofBase64(keyId, Base64.getEncoder().encodeToString(randomBytes(size)));
    }

    private static byte[] randomBytes(int size) {
        byte[] key = new byte[size];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
