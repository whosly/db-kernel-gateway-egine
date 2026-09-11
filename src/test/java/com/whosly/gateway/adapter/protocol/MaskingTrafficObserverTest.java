package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTrafficObserverTest {

    @Test
    void masksTheStatementBeforeTheSinkSeesIt() {
        List<DatabaseTrafficEvent> observed = new CopyOnWriteArrayList<>();
        DatabaseTrafficObserver sink = DatabaseTrafficObserver.masking(observed::add);
        DatabaseTrafficEvent event = DatabaseTrafficEvent
                .builder("MySQL", "s1", "COM_QUERY", "select * from t where id = 42 and name = 'alice'")
                .attribute("client.user", "appuser")
                .build();

        sink.onEvent(event);

        assertThat(observed).singleElement().satisfies(masked -> {
            assertThat(masked.getStatement()).isEqualTo("select * from t where id = ? and name = ?");
            assertThat(masked.getOperation()).isEqualTo("COM_QUERY");
            assertThat(masked.getSessionId()).isEqualTo("s1");
            assertThat(masked.getObservedAt()).isEqualTo(event.getObservedAt());
            assertThat(masked.getAttribute("client.user")).contains("appuser");
        });
    }

    @Test
    void keepsStatementsWithoutLiteralsUnchanged() {
        List<DatabaseTrafficEvent> observed = new CopyOnWriteArrayList<>();
        DatabaseTrafficObserver sink = DatabaseTrafficObserver.masking(observed::add);
        DatabaseTrafficEvent event = DatabaseTrafficEvent
                .builder("PostgreSQL", "s2", "QUERY", "select now()")
                .build();

        sink.onEvent(event);

        assertThat(observed).singleElement()
                .extracting(DatabaseTrafficEvent::getStatement)
                .isEqualTo("select now()");
    }
}
