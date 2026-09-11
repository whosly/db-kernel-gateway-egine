package com.whosly.gateway.adapter.protocol;

import com.whosly.gateway.parser.StatementClassifier;
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

        TrafficDecision decision = inspector.intercept(message(TrafficDirection.CLIENT_TO_TARGET));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(decision.message().mutated()).isFalse();
        assertThat(observed).containsExactly(event);
    }

    @Test
    void recordsStatementEffectsIntoTheSessionDirtinessModel() {
        ProtocolSession session = new ProtocolSession("MySQL", "mysql-dirtiness");
        DatabaseTrafficEvent event = DatabaseTrafficEvent
                .builder("MySQL", "mysql-dirtiness", "COM_QUERY", "set names utf8mb4")
                .build();
        DatabaseTrafficInspector inspector = new DatabaseTrafficInspector(
                (direction, bytes, offset, length) -> List.of(event),
                DatabaseTrafficObserver.noop(),
                DatabaseRiskPolicy.allowAll(),
                session,
                new StatementClassifier(null));

        TrafficDecision decision = inspector.intercept(message(TrafficDirection.CLIENT_TO_TARGET));

        assertThat(decision.action()).isEqualTo(TrafficAction.FORWARD);
        assertThat(session.getDirtiness().hasSessionSettings()).isTrue();
        assertThat(session.snapshot().isReusableWithoutReset()).isFalse();
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

        TrafficDecision decision = inspector.intercept(message(TrafficDirection.CLIENT_TO_TARGET));

        assertThat(decision.action()).isEqualTo(TrafficAction.DENY);
    }

    private static WireMessage message(TrafficDirection direction) {
        return RawBackedMessage.of(direction, new byte[]{1, 2, 3}, 0, 3);
    }
}
