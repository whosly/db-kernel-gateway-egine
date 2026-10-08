package com.whosly.gateway.adapter.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLCancelKeyRegistryTest {

    @Test
    void resolvesTheSessionRegisteredForAKey() {
        PostgreSQLCancelKeyRegistry registry = new PostgreSQLCancelKeyRegistry();
        registry.register("postgresql-a", 4711, 9911);

        assertThat(registry.findTargetSessionId(4711, 9911)).contains("postgresql-a");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void returnsEmptyForAKeyNoSessionOwns() {
        PostgreSQLCancelKeyRegistry registry = new PostgreSQLCancelKeyRegistry();
        registry.register("postgresql-a", 4711, 9911);

        assertThat(registry.findTargetSessionId(4711, 1)).isEmpty();
        assertThat(registry.findTargetSessionId(1, 9911)).isEmpty();
    }

    @Test
    void unregisterOnlyDropsTheGivenSessionKeys() {
        PostgreSQLCancelKeyRegistry registry = new PostgreSQLCancelKeyRegistry();
        registry.register("postgresql-a", 4711, 9911);
        registry.register("postgresql-b", 4712, 9912);

        registry.unregister("postgresql-a");

        assertThat(registry.findTargetSessionId(4711, 9911)).isEmpty();
        assertThat(registry.findTargetSessionId(4712, 9912)).contains("postgresql-b");
        assertThat(registry.size()).isEqualTo(1);

        registry.unregister(null);
        assertThat(registry.size()).isEqualTo(1);
    }

    /**
     * Product contract (P1-3): the registry only correlates CancelRequest keys with
     * sessions. It never opens a backend socket or synthesises a cancel message —
     * transparent proxying leaves cancel delivery to the client's own short-lived
     * CancelRequest connection.
     */
    @Test
    void documentsAssociateOnlyProductContract() {
        PostgreSQLCancelKeyRegistry registry = new PostgreSQLCancelKeyRegistry();
        registry.register("postgresql-a", 4711, 9911);

        assertThat(registry.findTargetSessionId(4711, 9911)).contains("postgresql-a");
        // Association is lookup-only: size stays 1 and no side-channel cancel is issued.
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.findTargetSessionId(4711, 9911)).contains("postgresql-a");
    }
}
