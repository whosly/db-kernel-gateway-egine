package com.whosly.gateway.adapter.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolSessionTest {

    @Test
    void startsConnectedAndMovesThroughStartupFlow() {
        ProtocolSession session = new ProtocolSession("mysql", "client-1");

        assertThat(session.getProtocolName()).isEqualTo("mysql");
        assertThat(session.getConnectionId()).isEqualTo("client-1");
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CONNECTED);

        session.transitionTo(ProtocolConnectionState.NEGOTIATING);
        session.transitionTo(ProtocolConnectionState.AUTHENTICATING);
        session.transitionTo(ProtocolConnectionState.READY);

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.READY);
    }

    @Test
    void rejectsIllegalTransitionFromConnectedToExecuting() {
        ProtocolSession session = new ProtocolSession("postgresql", "client-2");

        assertThatThrownBy(() -> session.transitionTo(ProtocolConnectionState.EXECUTING))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("Illegal protocol state transition");
    }

    @Test
    void tryTransitionToReportsIllegalTransitionWithoutThrowing() {
        ProtocolSession session = new ProtocolSession("mysql", "client-4");

        assertThat(session.tryTransitionTo(ProtocolConnectionState.EXECUTING)).isFalse();
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CONNECTED);

        assertThat(session.tryTransitionTo(ProtocolConnectionState.NEGOTIATING)).isTrue();
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.NEGOTIATING);
    }

    @Test
    void closeConvergesToClosedFromAnyState() {
        ProtocolSession session = new ProtocolSession("postgresql", "client-5");
        session.transitionTo(ProtocolConnectionState.NEGOTIATING);

        session.close();

        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CLOSED);
        session.close();
        assertThat(session.getState()).isEqualTo(ProtocolConnectionState.CLOSED);
    }

    @Test
    void storesSessionAttributesWithoutExposingMutableState() {
        ProtocolSession session = new ProtocolSession("postgresql", "client-3");

        session.putAttribute("database", "demo");

        assertThat(session.getAttribute("database")).contains("demo");
        assertThat(session.attributes()).containsEntry("database", "demo");
        assertThatThrownBy(() -> session.attributes().put("user", "root"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
