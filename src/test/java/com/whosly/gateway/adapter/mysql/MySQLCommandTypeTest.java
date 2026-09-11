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
    void mapsRefreshAndShutdownToTheirOfficialCodes() {
        assertThat(MySQLCommandType.fromCode(0x07)).contains(MySQLCommandType.COM_REFRESH);
        assertThat(MySQLCommandType.fromCode(0x08)).contains(MySQLCommandType.COM_SHUTDOWN);
    }

    @Test
    void resolvesEveryPreparedStatementCommand() {
        assertThat(MySQLCommandType.fromCode(0x16)).contains(MySQLCommandType.COM_STMT_PREPARE);
        assertThat(MySQLCommandType.fromCode(0x17)).contains(MySQLCommandType.COM_STMT_EXECUTE);
        assertThat(MySQLCommandType.fromCode(0x18)).contains(MySQLCommandType.COM_STMT_SEND_LONG_DATA);
        assertThat(MySQLCommandType.fromCode(0x19)).contains(MySQLCommandType.COM_STMT_CLOSE);
        assertThat(MySQLCommandType.fromCode(0x1A)).contains(MySQLCommandType.COM_STMT_RESET);
        assertThat(MySQLCommandType.fromCode(0x1C)).contains(MySQLCommandType.COM_STMT_FETCH);
    }

    @Test
    void returnsEmptyForUnknownCommandCode() {
        assertThat(MySQLCommandType.fromCode(0x7F)).isEmpty();
    }
}
