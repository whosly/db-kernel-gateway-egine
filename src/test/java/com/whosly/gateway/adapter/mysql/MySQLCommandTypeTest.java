package com.whosly.gateway.adapter.mysql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLCommandTypeTest {

    @Test
    void resolvesKnownCommandCode() {
        assertThat(MySQLCommandType.fromCode(0x03)).contains(MySQLCommandType.COM_QUERY);
        assertThat(MySQLCommandType.COM_QUERY.getCode()).isEqualTo(0x03);
    }

    @Test
    void returnsEmptyForUnknownCommandCode() {
        assertThat(MySQLCommandType.fromCode(0x7F)).isEmpty();
    }
}
