package com.whosly.gateway.console.persist;

import com.whosly.gateway.runtime.spi.PersistedInstance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingRuleStoreTest {

    private MaskingRuleStore store;
    private ConsoleInstanceStore instances;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:masking-store-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        instances = new ConsoleInstanceStore(jdbc);
        store = new MaskingRuleStore(jdbc);
    }

    @Test
    void insertFindUpdateDeleteAndCascadeByInstance() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        instances.upsert(new PersistedInstance(
                "inst-a", "A", "mysql", "0.0.0.0", 33310, true,
                "127.0.0.1", 3306, "db", "root", "secret",
                now, now));

        MaskingRuleRecord saved = store.insert(new MaskingRuleRecord(
                null, "inst-a", "email-null", "null", 10,
                "email", "users", null, null, null, null, null,
                true, null, null));
        assertThat(saved.id()).isNotBlank();
        assertThat(saved.instanceId()).isEqualTo("inst-a");
        assertThat(saved.strategy()).isEqualTo("null");
        assertThat(saved.columnName()).isEqualTo("email");

        assertThat(store.findByInstanceId("inst-a")).hasSize(1);

        MaskingRuleRecord updated = store.update(new MaskingRuleRecord(
                saved.id(), "inst-a", "email-fixed", "fixed", 20,
                "email", "users", null, "REDACTED", null, null, null,
                true, saved.createdAt(), null));
        assertThat(updated.name()).isEqualTo("email-fixed");
        assertThat(updated.fixedValue()).isEqualTo("REDACTED");
        assertThat(updated.priority()).isEqualTo(20);

        store.insert(new MaskingRuleRecord(
                null, "inst-a", "phone-partial", "partial", 5,
                "phone", null, null, null, 3, 4, null,
                true, null, null));
        assertThat(store.findByInstanceId("inst-a")).hasSize(2);

        assertThat(store.deleteById(saved.id(), "inst-a")).isTrue();
        assertThat(store.findByInstanceId("inst-a")).hasSize(1);

        int cascaded = store.deleteByInstanceId("inst-a");
        assertThat(cascaded).isEqualTo(1);
        assertThat(store.findByInstanceId("inst-a")).isEmpty();
    }

    @Test
    void replaceAllSwapsRules() {
        store.insert(new MaskingRuleRecord(
                null, "gw-1", "old", "null", 1,
                "a", null, null, null, null, null, null,
                true, null, null));
        List<MaskingRuleRecord> replaced = store.replaceAll("gw-1", List.of(
                new MaskingRuleRecord(
                        null, "gw-1", "new1", "hash", 2,
                        "email", null, null, null, null, null, 16,
                        true, null, null),
                new MaskingRuleRecord(
                        null, "gw-1", "new2", "null", 1,
                        "ssn", null, null, null, null, null, null,
                        false, null, null)
        ));
        assertThat(replaced).hasSize(2);
        assertThat(store.findByInstanceId("gw-1"))
                .extracting(MaskingRuleRecord::name)
                .containsExactlyInAnyOrder("new1", "new2");
    }
}
