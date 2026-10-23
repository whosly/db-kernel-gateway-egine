package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.RoutingContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerLogin7RoutingTest {

    @Test
    void mapsUsernameAndDatabaseToRoutingContext() {
        byte[] payload = Login7ObservationTest.buildLogin7Payload("sa", "master", "JDBC", "host");
        RoutingContext ctx = SqlServerLogin7Routing.fromLogin7Payload(payload);
        assertThat(ctx.username()).contains("sa");
        assertThat(ctx.database()).contains("master");
        assertThat(ctx.isEmpty()).isFalse();
    }

    @Test
    void returnsEmptyForUnusablePayload() {
        assertThat(SqlServerLogin7Routing.fromLogin7Payload(new byte[8]).isEmpty()).isTrue();
        assertThat(SqlServerLogin7Routing.fromLogin7Payload(null).isEmpty()).isTrue();
        assertThat(SqlServerLogin7Routing.fromObservation(null).isEmpty()).isTrue();
    }

    @Test
    void mapsUsernameOnly() {
        Login7Observation obs = Login7Observation.tryParse(
                Login7ObservationTest.buildLogin7Payload("onlyuser", "", "app", "h")).orElseThrow();
        RoutingContext ctx = SqlServerLogin7Routing.fromObservation(obs);
        assertThat(ctx.username()).contains("onlyuser");
        assertThat(ctx.database()).isEmpty();
    }
}
