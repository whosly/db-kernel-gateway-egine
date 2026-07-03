package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTrafficInspectorTest {

    @Test
    void observesExtractedSqlEventsBeforeForwardingBytes() {
        List<SqlTrafficEvent> observed = new CopyOnWriteArrayList<>();
        SqlTrafficEvent event = SqlTrafficEvent.builder("MySQL", "s1", "COM_QUERY", "select 1").build();
        SqlTrafficInspector inspector = new SqlTrafficInspector(
                (direction, bytes, offset, length) -> List.of(event),
                observed::add,
                SqlRiskPolicy.allowAll());

        TrafficAction action = inspector.onBytes(TrafficDirection.CLIENT_TO_TARGET,
                new byte[]{1, 2, 3}, 0, 3);

        assertThat(action).isEqualTo(TrafficAction.FORWARD);
        assertThat(observed).containsExactly(event);
    }

    @Test
    void closesConnectionWhenRiskPolicyDeniesSqlEvent() {
        SqlTrafficEvent event = SqlTrafficEvent.builder("MySQL", "s1", "COM_QUERY", "drop table account").build();
        SqlTrafficInspector inspector = new SqlTrafficInspector(
                (direction, bytes, offset, length) -> List.of(event),
                SqlTrafficObserver.noop(),
                sqlEvent -> RiskDecision.deny("dangerous ddl"));

        TrafficAction action = inspector.onBytes(TrafficDirection.CLIENT_TO_TARGET,
                new byte[]{1, 2, 3}, 0, 3);

        assertThat(action).isEqualTo(TrafficAction.CLOSE);
    }

    @Test
    void passesTargetBytesToExtractorForProtocolStateTracking() {
        SqlTrafficInspector inspector = new SqlTrafficInspector(
                (direction, bytes, offset, length) -> {
                    assertThat(direction).isEqualTo(TrafficDirection.TARGET_TO_CLIENT);
                    return List.of();
                },
                SqlTrafficObserver.noop(),
                SqlRiskPolicy.allowAll());

        TrafficAction action = inspector.onBytes(TrafficDirection.TARGET_TO_CLIENT,
                new byte[]{1, 2, 3}, 0, 3);

        assertThat(action).isEqualTo(TrafficAction.FORWARD);
    }
}
