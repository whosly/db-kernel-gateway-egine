package com.whosly.gateway.console.persist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceAclStoreTest {

    private InstanceAclStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:acl-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        store = new InstanceAclStore(new JdbcTemplate(ds));
    }

    @Test
    void upsertReplaceAndMatch() {
        var a = store.upsert("user", "alice", "gw-1", "instances:read,sql:execute");
        assertThat(a.id()).isNotBlank();
        assertThat(store.findByInstanceId("gw-1")).hasSize(1);

        var again = store.upsert("user", "alice", "gw-1", "instances:read");
        assertThat(again.id()).isEqualTo(a.id());
        assertThat(again.permissions()).isEqualTo("instances:read");

        store.upsert("role", "OPERATOR", "gw-2", "instances:read,instances:start_stop");
        assertThat(store.findMatching("alice", List.of("CONSOLE_OPERATOR")))
                .extracting(InstanceAclStore.InstanceAclRecord::instanceId)
                .containsExactlyInAnyOrder("gw-1", "gw-2");

        store.replaceForInstance("gw-1", List.of(
                new InstanceAclStore.AclWriteItem("user", "bob", "instances:read")));
        assertThat(store.findByInstanceId("gw-1")).hasSize(1);
        assertThat(store.findByInstanceId("gw-1").get(0).principal()).isEqualTo("bob");

        assertThat(store.delete(store.findByInstanceId("gw-1").get(0).id())).isTrue();
        assertThat(store.findByInstanceId("gw-1")).isEmpty();
    }

    @Test
    void rejectsBadPrincipalType() {
        assertThatThrownBy(() -> store.upsert("group", "x", "gw-1", "instances:read"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
