package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleSqlHistoryStoreTest {

    private ConsoleSqlHistoryStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:sql_history_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new ConsoleSqlHistoryStore(new JdbcTemplate(ds));
    }

    @Test
    void insertListAndPrune() {
        for (int i = 0; i < 210; i++) {
            store.insert("inst-1", "SELECT " + i, true, 10L, 1, "console");
        }
        assertThat(store.count()).isLessThanOrEqualTo(ConsoleSqlHistoryStore.MAX_ROWS);
        assertThat(store.list("inst-1", 50)).hasSize(50);
        assertThat(store.list("inst-1", 50).get(0).sqlText()).contains("SELECT");
        assertThat(store.deleteAll()).isPositive();
        assertThat(store.count()).isZero();
    }

    @Test
    void deleteById() {
        var row = store.insert("inst-2", "SELECT 1", false, null, null, "console");
        assertThat(store.deleteById(row.id())).isTrue();
        assertThat(store.deleteById(row.id())).isFalse();
    }
}
