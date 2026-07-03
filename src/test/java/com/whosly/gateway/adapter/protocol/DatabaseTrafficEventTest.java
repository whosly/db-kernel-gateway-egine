package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTrafficEventTest {

    @Test
    void buildsSqlStatementEventWithoutSqlSpecificTypeName() {
        DatabaseTrafficEvent event = DatabaseTrafficEvent
                .builder("MySQL", "s1", "COM_QUERY", "select 1")
                .attribute("schema", "mysql")
                .build();

        assertThat(event.getProtocolName()).isEqualTo("MySQL");
        assertThat(event.getSessionId()).isEqualTo("s1");
        assertThat(event.getOperation()).isEqualTo("COM_QUERY");
        assertThat(event.getStatement()).isEqualTo("select 1");
        assertThat(event.getAttribute("schema")).contains("mysql");
    }

    @Test
    void buildsCommandEventForNonSqlProtocols() {
        DatabaseTrafficEvent event = DatabaseTrafficEvent
                .builder("Redis", "r1", "GET", "GET account:1")
                .attribute("key", "account:1")
                .build();

        assertThat(event.getProtocolName()).isEqualTo("Redis");
        assertThat(event.getOperation()).isEqualTo("GET");
        assertThat(event.getStatement()).isEqualTo("GET account:1");
        assertThat(event.getAttribute("key")).contains("account:1");
    }
}
