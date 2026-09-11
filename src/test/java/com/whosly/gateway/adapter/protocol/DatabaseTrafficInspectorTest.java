package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTrafficInspectorTest {

    @Test
    void observesExtractedDatabaseEventsBeforeForwardingBytes() {
        List<DatabaseTrafficEvent> observed = new CopyOnWriteArrayList<>();
        DatabaseTrafficEvent event = DatabaseTrafficEvent
                .builder("SQLServer", "s1", "SQL_BATCH", "select 1")
                .build();
        DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                (direction, bytes, offset, length) -> List.of(event),
                observed::add,
                DatabaseRiskPolicy.allowAll());

        TrafficAction action = inspector.onBytes(TrafficDirection.CLIENT_TO_TARGET,
                new byte[]{1, 2, 3}, 0, 3);

        assertThat(action).isEqualTo(TrafficAction.FORWARD);
        assertThat(observed).containsExactly(event);
    }

    @Test
    void closesConnectionWhenRiskPolicyDeniesDatabaseEvent() {
        DatabaseTrafficEvent event = DatabaseTrafficEvent
                .builder("Redis", "r1", "DEL", "DEL account:1")
                .build();
        DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                (direction, bytes, offset, length) -> List.of(event),
                DatabaseTrafficObserver.noop(),
                databaseEvent -> RiskDecision.deny("dangerous command"));

        TrafficAction action = inspector.onBytes(TrafficDirection.CLIENT_TO_TARGET,
                new byte[]{1, 2, 3}, 0, 3);

        assertThat(action).isEqualTo(TrafficAction.CLOSE);
    }
}
