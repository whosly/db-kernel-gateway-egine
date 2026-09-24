package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleAuditStoreTest {

    private ConsoleAuditStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:console-audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new ConsoleAuditStore(new JdbcTemplate(ds));
    }

    @Test
    void insertAndListRecent() {
        store.insert("instance.create", "gw-1", "{\"dbType\":\"mysql\"}", "console");
        store.insert("instance.start", "gw-1", "{\"ok\":true}", "console");
        List<ConsoleAuditStore.ConsoleAuditRecord> rows = store.listRecent(10);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).action()).isEqualTo("instance.start");
        assertThat(rows.get(0).detailJson()).doesNotContain("password");
    }
}
