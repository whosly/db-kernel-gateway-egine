package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleInstanceStoreTest {

    private ConsoleInstanceStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:console-store-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new ConsoleInstanceStore(new JdbcTemplate(ds));
    }

    @Test
    void upsertFindAllAndDelete() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        store.upsert(new ConsoleInstanceRecord(
                "rt-1", "测试", "mysql", "0.0.0.0", 33310, true,
                "127.0.0.1", 3306, "db", "root", "secret-lab",
                now, now));

        List<ConsoleInstanceRecord> all = store.findAll();
        assertThat(all).hasSize(1);
        ConsoleInstanceRecord row = all.get(0);
        assertThat(row.id()).isEqualTo("rt-1");
        assertThat(row.dbType()).isEqualTo("mysql");
        assertThat(row.targetPassword()).isEqualTo("secret-lab");
        assertThat(row.listenPort()).isEqualTo(33310);

        assertThat(store.deleteById("rt-1")).isTrue();
        assertThat(store.findAll()).isEmpty();
        assertThat(store.deleteById("missing")).isFalse();
    }
}
