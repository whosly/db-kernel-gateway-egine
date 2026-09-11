package com.whosly.gateway.adapter.protocol;

import com.whosly.gateway.parser.StatementEffect;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
    void tracksObservationConfidenceAndNeverUpgradesASuspendedSession() {
        ProtocolSession session = new ProtocolSession("mysql", "client-6");

        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.CONFIRMED);
        assertThat(session.isObservationTrusted()).isTrue();

        session.markObservationUncertain();
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.UNCERTAIN);

        session.suspendObservation();
        session.markObservationUncertain();
        assertThat(session.getObservationConfidence()).isEqualTo(ObservationConfidence.SUSPENDED);
        assertThat(session.isObservationTrusted()).isFalse();

        session.confirmObservation();
        assertThat(session.isObservationTrusted()).isTrue();
    }

    @Test
    void tracksSessionDirtinessFromObservedStatements() {
        ProtocolSession session = new ProtocolSession("postgresql", "client-7");

        assertThat(session.getDirtiness()).isEqualTo(SessionDirtiness.clean());
        assertThat(session.snapshot().isReusableWithoutReset()).isTrue();

        session.recordStatementEffects(Set.of(StatementEffect.READ));
        assertThat(session.getDirtiness().isClean()).isTrue();

        session.recordStatementEffects(Set.of(StatementEffect.SESSION_SETTING));
        assertThat(session.getDirtiness().hasSessionSettings()).isTrue();
        assertThat(session.getDirtiness().isClean()).isFalse();

        session.recordStatementEffects(Set.of(StatementEffect.UNKNOWN));
        assertThat(session.getDirtiness().tooComplexToReset()).isTrue();
        assertThat(session.snapshot().isReusableWithoutReset()).isFalse();
    }

    @Test
    void exposesIdentityTransactionAndPreparedStatementsInTheSnapshot() {
        ProtocolSession session = new ProtocolSession("mysql", "client-8");
        session.putAttribute("client.user", "appuser");
        session.putAttribute("client.database", "demo");
        session.setInTransaction(true);
        session.markPreparedStatementOpened();
        session.markPreparedStatementOpened();
        session.markPreparedStatementClosed();

        SessionSnapshot snapshot = session.snapshot();

        assertThat(snapshot.protocolName()).isEqualTo("mysql");
        assertThat(snapshot.connectionId()).isEqualTo("client-8");
        assertThat(snapshot.clientUser()).contains("appuser");
        assertThat(snapshot.clientDatabase()).contains("demo");
        assertThat(snapshot.inTransaction()).isTrue();
        assertThat(snapshot.dirtiness().hasPreparedStatements()).isTrue();
        assertThat(snapshot.isReusableWithoutReset()).isFalse();
        assertThat(snapshot.connectedAt()).isNotNull();
        assertThat(snapshot.lastActivity()).isNotNull();

        session.clearPreparedStatements();
        session.setInTransaction(false);

        assertThat(session.getOpenPreparedStatements()).isZero();
        assertThat(session.snapshot().isReusableWithoutReset()).isTrue();
    }

    @Test
    void neverClosesMorePreparedStatementsThanItOpened() {
        ProtocolSession session = new ProtocolSession("mysql", "client-10");

        session.markPreparedStatementClosed();

        assertThat(session.getOpenPreparedStatements()).isZero();
        assertThat(session.getDirtiness().hasPreparedStatements()).isFalse();
    }

    @Test
    void refusesReuseWhileObservationIsNotTrusted() {
        ProtocolSession session = new ProtocolSession("mysql", "client-9");
        session.suspendObservation();

        assertThat(session.snapshot().isObservationTrusted()).isFalse();
        assertThat(session.snapshot().isReusableWithoutReset()).isFalse();
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
