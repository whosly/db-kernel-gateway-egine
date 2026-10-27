package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertChannelStoreTest {

    private AlertChannelStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:alert-ch-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new AlertChannelStore(new JdbcTemplate(ds));
    }

    @Test
    void crudAndDeliveryState() {
        AlertChannelStore.AlertChannelRecord saved = store.insert(new AlertChannelStore.AlertChannelRecord(
                null, "hook", "WEBHOOK", true, "WARN", 120, "{\"url\":\"http://x\"}",
                null, null, null, null, null, null));
        assertThat(saved.id()).isNotBlank();
        assertThat(store.findEnabled()).hasSize(1);

        store.updateDeliveryState(saved.id(), true, null, 200, Instant.parse("2026-10-27T01:00:00Z"));
        AlertChannelStore.AlertChannelRecord loaded = store.findById(saved.id()).orElseThrow();
        assertThat(loaded.lastOk()).isTrue();
        assertThat(loaded.lastStatusCode()).isEqualTo(200);

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(store.findAll()).isEmpty();
    }
}
