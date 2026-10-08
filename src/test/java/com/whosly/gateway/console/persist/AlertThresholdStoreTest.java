package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AlertThresholdStoreTest {

    private AlertThresholdStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:alert-thr-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new AlertThresholdStore(new JdbcTemplate(ds));
    }

    @Test
    void crudAndEvalState() {
        assertThat(store.findAll()).isEmpty();
        var saved = store.insert(new AlertThresholdStore.AlertThresholdRecord(
                null, "会话偏高", "active_sessions", "GT", 10d, null, null,
                true, "WARN", null, null, false, null, null));
        assertThat(saved.id()).isNotBlank();
        assertThat(store.findById(saved.id())).isPresent();
        assertThat(store.findEnabled()).hasSize(1);

        store.updateEvalState(saved.id(), true, 12d, java.time.Instant.parse("2026-01-01T00:00:00Z"));
        var after = store.findById(saved.id()).orElseThrow();
        assertThat(after.lastFiring()).isTrue();
        assertThat(after.lastValue()).isEqualTo(12d);
        assertThat(after.lastFiredAt()).isNotNull();

        store.update(new AlertThresholdStore.AlertThresholdRecord(
                saved.id(), "会话偏高-改", "active_sessions", "GTE", 5d, 30, "gw-1",
                false, "CRITICAL", after.lastFiredAt(), after.lastValue(), true,
                after.createdAt(), null));
        var updated = store.findById(saved.id()).orElseThrow();
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.instanceId()).isEqualTo("gw-1");
        assertThat(store.findEnabled()).isEmpty();

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(store.findById(saved.id())).isEmpty();
    }
}
