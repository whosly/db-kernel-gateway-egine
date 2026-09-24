package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyStoreTest {

    private RiskPolicyStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:risk-policy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new RiskPolicyStore(new JdbcTemplate(ds));
    }

    @Test
    void upsertAndFindSingleton() {
        assertThat(store.find()).isEmpty();
        RiskPolicyStore.RiskPolicyRecord saved = store.upsert(
                true, List.of("COM_QUIT"), List.of("drop table"));
        assertThat(saved.id()).isEqualTo(RiskPolicyStore.SINGLETON_ID);
        assertThat(store.find()).isPresent();
        assertThat(store.find().orElseThrow().deniedOperations()).containsExactly("COM_QUIT");
        assertThat(store.find().orElseThrow().deniedStatementKeywords()).containsExactly("drop table");

        store.upsert(false, List.of(), List.of());
        assertThat(store.find().orElseThrow().enabled()).isFalse();
        assertThat(store.find().orElseThrow().deniedOperations()).isEmpty();
    }
}
