package com.whosly.gateway.console.persist;

import com.whosly.gateway.console.security.ConsoleSecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleInstanceStoreEncryptionTest {

    private ConsoleInstanceStore store;
    private ConsoleSecretCipher cipher;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (i * 3);
        }
        cipher = ConsoleSecretCipher.fromBase64MasterKey(Base64.getEncoder().encodeToString(key));
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:console-enc-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new ConsoleInstanceStore(new JdbcTemplate(ds), cipher);
    }

    @Test
    void encryptsOnUpsertDecryptsOnLoad() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        store.upsert(new ConsoleInstanceRecord(
                "rt-enc", "加密测试", "mysql", "0.0.0.0", 33311, true,
                "127.0.0.1", 3306, "db", "root", "Aa123456.",
                now, now));

        String sealed = store.findSealedPassword("rt-enc").orElseThrow();
        assertThat(sealed).startsWith("enc:v1:");
        assertThat(sealed).doesNotContain("Aa123456.");

        ConsoleInstanceRecord loaded = store.findById("rt-enc").orElseThrow();
        assertThat(loaded.targetPassword()).isEqualTo("Aa123456.");
    }

    @Test
    void legacyPlaintextRowStillLoads() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:console-legacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ConsoleInstanceStore labStore = new ConsoleInstanceStore(jdbc); // no master key
        Instant now = Instant.now();
        labStore.upsert(new ConsoleInstanceRecord(
                "legacy", "旧", "mysql", "0.0.0.0", 1, true,
                "127.0.0.1", 3306, "db", "u", "plain-legacy",
                now, now));
        // Re-open with cipher that has a key — legacy (no prefix) still readable
        ConsoleInstanceStore withKey = new ConsoleInstanceStore(jdbc, cipher);
        assertThat(withKey.findById("legacy").orElseThrow().targetPassword()).isEqualTo("plain-legacy");
    }

    @Test
    void requireModeRejectsUpsertWithoutMasterKey() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:console-require-enc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        ConsoleSecretCipher required = ConsoleSecretCipher.fromBase64MasterKey("", true);
        ConsoleInstanceStore blocked = new ConsoleInstanceStore(new JdbcTemplate(ds), required);
        Instant now = Instant.now();
        assertThatThrownBy(() ->
                blocked.upsert(new ConsoleInstanceRecord(
                        "blocked", "拒", "mysql", "0.0.0.0", 2, true,
                        "127.0.0.1", 3306, "db", "u", "plain-not-allowed",
                        now, now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require-secret-encryption");
    }

    @Test
    void labDefaultStillAllowsPlaintextUpsert() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:console-lab-plain;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        ConsoleInstanceStore lab = new ConsoleInstanceStore(new JdbcTemplate(ds));
        Instant now = Instant.now();
        lab.upsert(new ConsoleInstanceRecord(
                "lab", "实验室", "mysql", "0.0.0.0", 3, true,
                "127.0.0.1", 3306, "db", "u", "lab-plain",
                now, now));
        assertThat(lab.findSealedPassword("lab").orElseThrow()).isEqualTo("lab-plain");
        assertThat(lab.findById("lab").orElseThrow().targetPassword()).isEqualTo("lab-plain");
    }
}
