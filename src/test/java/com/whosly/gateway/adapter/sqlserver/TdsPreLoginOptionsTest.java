package com.whosly.gateway.adapter.sqlserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TdsPreLoginOptionsTest {

    @Test
    void readsEncryptionFromMinimalPayload() {
        byte[] payload = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_OFF);
        assertThat(TdsPreLoginOptions.encryptionValue(payload)).hasValue(TdsPreLoginOptions.ENCRYPT_OFF);
    }

    @Test
    void requiresEncryptionWhenClientAsksOn() {
        byte[] client = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_ON);
        byte[] server = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_OFF);
        assertThat(TdsPreLoginOptions.requiresEncryptionBeforeLogin7(client, server)).isTrue();
    }

    @Test
    void requiresEncryptionWhenServerRequires() {
        byte[] client = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_OFF);
        byte[] server = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_REQ);
        assertThat(TdsPreLoginOptions.requiresEncryptionBeforeLogin7(client, server)).isTrue();
    }

    @Test
    void cleartextWhenBothOff() {
        byte[] client = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_OFF);
        byte[] server = TdsPreLoginOptions.buildMinimal(TdsPreLoginOptions.ENCRYPT_OFF);
        assertThat(TdsPreLoginOptions.requiresEncryptionBeforeLogin7(client, server)).isFalse();
    }
}
