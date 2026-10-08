package com.whosly.gateway.console.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleSecretCipherTest {

    private static String key32() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (i + 1);
        }
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripWithMasterKey() {
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(key32());

        String sealed = cipher.sealForStorage("Aa123456.");
        assertThat(sealed).startsWith(ConsoleSecretCipher.PREFIX);
        assertThat(sealed).doesNotContain("Aa123456.");
        assertThat(cipher.openFromStorage(sealed)).isEqualTo("Aa123456.");
        assertThat(cipher.isRequireSecretEncryption()).isFalse();
        assertThat(cipher.allowsPlaintextWrites()).isFalse();
    }

    @Test
    void legacyPlaintextLoadsWithoutPrefix() {
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(key32());
        assertThat(cipher.openFromStorage("legacy-clear")).isEqualTo("legacy-clear");
    }

    @Test
    void labModeLeavesPlaintext() {
        ConsoleSecretCipher lab = ConsoleSecretCipher.fromBase64MasterKey("");
        assertThat(lab.isMasterKeyConfigured()).isFalse();
        assertThat(lab.isRequireSecretEncryption()).isFalse();
        assertThat(lab.allowsPlaintextWrites()).isTrue();
        assertThat(lab.sealForStorage("lab-pass")).isEqualTo("lab-pass");
        assertThatThrownBy(() -> lab.requireSeal("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-key-base64");
        assertThat(lab.status().get("storageMode")).isEqualTo("lab-plaintext");
    }

    @Test
    void requireModeRejectsPlaintextWriteWithoutKey() {
        ConsoleSecretCipher required = ConsoleSecretCipher.fromBase64MasterKey(null, true);
        assertThat(required.isRequireSecretEncryption()).isTrue();
        assertThat(required.isMasterKeyConfigured()).isFalse();
        assertThat(required.allowsPlaintextWrites()).isFalse();
        assertThat(required.status().get("storageMode")).isEqualTo("require-encrypted-blocked");

        assertThatThrownBy(() -> required.sealForStorage("must-not-store-plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require-secret-encryption")
                .hasMessageContaining("secret-key-base64");

        // empty / already-sealed pass through
        assertThat(required.sealForStorage("")).isEqualTo("");
        assertThat(required.sealForStorage(null)).isNull();
    }

    @Test
    void requireModeWithKeyEncrypts() {
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(key32(), true);
        String sealed = cipher.sealForStorage("prod-secret");
        assertThat(sealed).startsWith(ConsoleSecretCipher.PREFIX);
        assertThat(cipher.openFromStorage(sealed)).isEqualTo("prod-secret");
        assertThat(cipher.status().get("storageMode")).isEqualTo("encrypted");
    }

    @Test
    void requireModeStillReadsLegacyPlaintext() {
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(key32(), true);
        assertThat(cipher.openFromStorage("legacy-lab-row")).isEqualTo("legacy-lab-row");
    }

    @Test
    void rejectWrongKeyLength() {
        assertThatThrownBy(() -> ConsoleSecretCipher.fromBase64MasterKey(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void statusMapHasNoKeyMaterial() {
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(key32(), true);
        Map<String, Object> status = cipher.status();
        assertThat(status.toString()).doesNotContain(key32());
        assertThat(status).containsKeys(
                "masterKeyConfigured", "requireSecretEncryption",
                "allowsPlaintextWrites", "storageMode", "help");
    }
}
