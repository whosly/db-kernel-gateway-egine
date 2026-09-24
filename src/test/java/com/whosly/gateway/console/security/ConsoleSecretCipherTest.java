package com.whosly.gateway.console.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleSecretCipherTest {

    @Test
    void roundTripWithMasterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (i + 1);
        }
        String b64 = Base64.getEncoder().encodeToString(key);
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(b64);

        String sealed = cipher.sealForStorage("Aa123456.");
        assertThat(sealed).startsWith(ConsoleSecretCipher.PREFIX);
        assertThat(sealed).doesNotContain("Aa123456.");
        assertThat(cipher.openFromStorage(sealed)).isEqualTo("Aa123456.");
    }

    @Test
    void legacyPlaintextLoadsWithoutPrefix() {
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(
                Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(cipher.openFromStorage("legacy-clear")).isEqualTo("legacy-clear");
    }

    @Test
    void labModeLeavesPlaintext() {
        ConsoleSecretCipher lab = ConsoleSecretCipher.fromBase64MasterKey("");
        assertThat(lab.isMasterKeyConfigured()).isFalse();
        assertThat(lab.sealForStorage("lab-pass")).isEqualTo("lab-pass");
        assertThatThrownBy(() -> lab.requireSeal("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-key-base64");
    }

    @Test
    void rejectWrongKeyLength() {
        assertThatThrownBy(() -> ConsoleSecretCipher.fromBase64MasterKey(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
