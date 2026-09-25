package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.ConsoleSecretsStore;
import com.whosly.gateway.console.persist.MaskingRuleRecord;
import com.whosly.gateway.console.persist.MaskingRuleStore;
import com.whosly.gateway.masking.MaskingCipher;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsoleMaskingKeyServiceTest {

    private ConsoleMaskingKeyService service;
    private ConsoleMaskingKeyHolder holder;
    private GatewayListenerRuntime runtime;
    private MaskingRuleStore ruleStore;
    private byte[] masterKeyBytes;

    @BeforeEach
    void setUp() {
        masterKeyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            masterKeyBytes[i] = (byte) (i + 7);
        }
        ConsoleSecretCipher cipher = ConsoleSecretCipher.fromBase64MasterKey(
                Base64.getEncoder().encodeToString(masterKeyBytes));
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:masking-key-svc-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ConsoleSecretsStore secrets = new ConsoleSecretsStore(jdbc, cipher);
        ruleStore = new MaskingRuleStore(jdbc);
        holder = new ConsoleMaskingKeyHolder(null, "default");
        runtime = mock(GatewayListenerRuntime.class);
        when(runtime.reloadAllMasking()).thenReturn(Map.of("ok", true, "reloaded", 0));
        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayListenerRuntime> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtime);
        @SuppressWarnings("unchecked")
        ObjectProvider<MaskingRuleStore> ruleProvider = mock(ObjectProvider.class);
        when(ruleProvider.getIfAvailable()).thenReturn(ruleStore);
        ConsoleAuditService audit = new ConsoleAuditService(
                new com.whosly.gateway.console.persist.ConsoleAuditStore(jdbc));
        service = new ConsoleMaskingKeyService(
                secrets, cipher, holder, provider, audit, ruleProvider, "", "default");
    }

    @Test
    void statusNoneInitially() {
        Map<String, Object> status = service.status();
        assertThat(status.get("configured")).isEqualTo(false);
        assertThat(status.get("source")).isEqualTo("none");
        assertThat(status.get("encryptRulesCanBind")).isEqualTo(false);
        assertThat(status.get("encryptRulesWithoutKey")).isEqualTo(0);
        assertThat(status).doesNotContainKey("keyBase64");
    }

    @Test
    void statusCountsEncryptRulesWithoutKey() {
        ruleStore.insert(new MaskingRuleRecord(
                null, "inst-a", "enc-col", "encrypt", 1,
                "secret", null, null, null, null, null, null,
                true, null, null));
        Map<String, Object> status = service.status();
        assertThat(status.get("encryptRuleCount")).isEqualTo(1);
        assertThat(status.get("encryptRulesWithoutKey")).isEqualTo(1);
        assertThat(status.get("warning")).asString().contains("encrypt");
    }

    @Test
    void putAndClearKey() {
        byte[] maskingKey = new byte[32];
        for (int i = 0; i < 32; i++) {
            maskingKey[i] = (byte) (i + 1);
        }
        String keyB64 = Base64.getEncoder().encodeToString(maskingKey);
        Map<String, Object> put = service.putKey("k1", keyB64);
        assertThat(put.get("configured")).isEqualTo(true);
        assertThat(put.get("keyId")).isEqualTo("k1");
        assertThat(put.get("activeKeyId")).isEqualTo("k1");
        assertThat(put.get("source")).isEqualTo("console");
        assertThat(put.get("encryptRulesCanBind")).isEqualTo(true);
        assertThat(put.toString()).doesNotContain(keyB64);
        assertThat(holder.configured()).isTrue();
        verify(runtime).reloadAllMasking();

        Map<String, Object> cleared = service.clearKey();
        assertThat(cleared.get("configured")).isEqualTo(false);
        assertThat(holder.configured()).isFalse();
    }

    @Test
    void rotationKeepsPreviousKeyForDecrypt() {
        byte[] key1 = new byte[32];
        byte[] key2 = new byte[32];
        for (int i = 0; i < 32; i++) {
            key1[i] = (byte) (i + 1);
            key2[i] = (byte) (i + 50);
        }
        service.putKey("k1", Base64.getEncoder().encodeToString(key1));
        String underK1 = holder.cipher().orElseThrow().encrypt("k1", "old-payload");

        Map<String, Object> rotated = service.putKey(
                "k2", Base64.getEncoder().encodeToString(key2), "k1", true);
        assertThat(rotated.get("activeKeyId")).isEqualTo("k2");
        assertThat(rotated.get("previousKeyIds")).asList().contains("k1");

        MaskingCipher ring = holder.cipher().orElseThrow();
        assertThat(ring.decrypt(underK1)).isEqualTo("old-payload");
        String underK2 = ring.encrypt("k2", "new-payload");
        assertThat(MaskingCipher.extractKeyId(underK2)).contains("k2");
        assertThat(ring.decrypt(underK2)).isEqualTo("new-payload");
    }

    @Test
    void verifyRoundTripWithoutLeakingKey() {
        byte[] maskingKey = new byte[32];
        for (int i = 0; i < 32; i++) {
            maskingKey[i] = (byte) (i + 3);
        }
        String keyB64 = Base64.getEncoder().encodeToString(maskingKey);
        service.putKey("vk", keyB64);
        Map<String, Object> result = service.verify();
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("keyId")).isEqualTo("vk");
        assertThat(result.toString()).doesNotContain(keyB64);
        assertThat(result.toString()).doesNotContain(MaskingCipher.WIRE_PREFIX);
    }

    @Test
    void verifyFailsWithoutKey() {
        assertThatThrownBy(() -> service.verify())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无可用脱敏密钥");
    }

    @Test
    void putRequiresMasterKey() {
        ConsoleSecretCipher lab = ConsoleSecretCipher.fromBase64MasterKey("");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:masking-key-lab-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        ConsoleSecretsStore secrets = new ConsoleSecretsStore(new JdbcTemplate(ds), lab);
        ConsoleMaskingKeyHolder h = new ConsoleMaskingKeyHolder(null, "default");
        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayListenerRuntime> provider = mock(ObjectProvider.class);
        ConsoleAuditService audit = new ConsoleAuditService(
                new com.whosly.gateway.console.persist.ConsoleAuditStore(new JdbcTemplate(ds)));
        ConsoleMaskingKeyService labSvc = new ConsoleMaskingKeyService(
                secrets, lab, h, provider, audit, "", "default");
        assertThatThrownBy(() -> labSvc.putKey("k", Base64.getEncoder().encodeToString(new byte[32])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-key-base64");
    }
}
