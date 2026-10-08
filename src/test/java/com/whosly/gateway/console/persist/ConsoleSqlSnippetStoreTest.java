package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleSqlSnippetStoreTest {

    private ConsoleSqlSnippetStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:sql_snippet_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new ConsoleSqlSnippetStore(new JdbcTemplate(ds));
    }

    @Test
    void crud() {
        var created = store.insert("demo", "SELECT 1");
        assertThat(created.id()).isNotBlank();
        assertThat(store.listAll()).hasSize(1);
        var updated = store.update(created.id(), "demo2", "SELECT 2");
        assertThat(updated.name()).isEqualTo("demo2");
        assertThat(updated.sqlText()).isEqualTo("SELECT 2");
        assertThat(store.delete(created.id())).isTrue();
        assertThat(store.listAll()).isEmpty();
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> store.insert(" ", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
