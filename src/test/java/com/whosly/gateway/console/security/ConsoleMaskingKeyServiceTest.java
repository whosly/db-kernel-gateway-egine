package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.ConsoleSecretsStore;
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
        ds.setUrl("jdbc:h2:mem:masking-key-svc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        ConsoleSecretsStore secrets = new ConsoleSecretsStore(new JdbcTemplate(ds), cipher);
        holder = new ConsoleMaskingKeyHolder(null, "default");
        runtime = mock(GatewayListenerRuntime.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayListenerRuntime> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtime);
        ConsoleAuditService audit = new ConsoleAuditService(
                new com.whosly.gateway.console.persist.ConsoleAuditStore(new JdbcTemplate(ds)));
        service = new ConsoleMaskingKeyService(
                secrets, cipher, holder, provider, audit, "", "default");
    }

    @Test
    void statusNoneInitially() {
        Map<String, Object> status = service.status();
        assertThat(status.get("configured")).isEqualTo(false);
        assertThat(status.get("source")).isEqualTo("none");
        assertThat(status).doesNotContainKey("keyBase64");
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
        assertThat(put.get("source")).isEqualTo("console");
        assertThat(put.toString()).doesNotContain(keyB64);
        assertThat(holder.configured()).isTrue();
        verify(runtime).reloadAllMasking();

        Map<String, Object> cleared = service.clearKey();
        assertThat(cleared.get("configured")).isEqualTo(false);
        assertThat(holder.configured()).isFalse();
    }

    @Test
    void putRequiresMasterKey() {
        ConsoleSecretCipher lab = ConsoleSecretCipher.fromBase64MasterKey("");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:masking-key-lab;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
