package com.whosly.gateway.adapter.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSQLMessageTypeTest {

    @Test
    void resolvesKnownFrontendMessageCode() {
        assertThat(PostgreSQLMessageType.fromCode('Q')).contains(PostgreSQLMessageType.QUERY);
        assertThat(PostgreSQLMessageType.QUERY.getCode()).isEqualTo('Q');
    }

    @Test
    void returnsEmptyForUnknownMessageCode() {
        assertThat(PostgreSQLMessageType.fromCode('?')).isEmpty();
    }
}
