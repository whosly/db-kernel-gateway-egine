package com.whosly.gateway.adapter.mysql;

import com.whosly.gateway.adapter.protocol.ProtocolConnectionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLSessionTest {

    @Test
    void tracksCapabilitiesDatabaseAndSequence() {
        MySQLSession session = new MySQLSession("mysql-1");

        session.setClientCapabilities(0x00000200L);
        session.setCurrentDatabase("demo");

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CONNECTED);
        assertThat(session.getClientCapabilities()).isEqualTo(0x00000200L);
        assertThat(session.getCurrentDatabase()).contains("demo");
        assertThat(session.nextServerSequence()).isEqualTo(1);
        assertThat(session.nextServerSequence()).isEqualTo(2);

        session.resetSequence();

        assertThat(session.nextServerSequence()).isEqualTo(1);
    }
}
