package com.whosly.gateway.adapter.protocol;

import com.whosly.gateway.parser.StatementEffect;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-client protocol state shared by protocol-specific sessions.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class ProtocolSession {

    private static final Map<ProtocolConnectionState, Set<ProtocolConnectionState>> ALLOWED_TRANSITIONS =
            new EnumMap<>(ProtocolConnectionState.class);

    static {
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.CONNECTED,
                EnumSet.of(ProtocolConnectionState.NEGOTIATING, ProtocolConnectionState.AUTHENTICATING,
                        ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.NEGOTIATING,
                EnumSet.of(ProtocolConnectionState.AUTHENTICATING, ProtocolConnectionState.READY,
                        ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.AUTHENTICATING,
                EnumSet.of(ProtocolConnectionState.READY, ProtocolConnectionState.CLOSING,
                        ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.READY,
                EnumSet.of(ProtocolConnectionState.EXECUTING, ProtocolConnectionState.STREAMING,
                        ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.EXECUTING,
                EnumSet.of(ProtocolConnectionState.READY, ProtocolConnectionState.STREAMING,
                        ProtocolConnectionState.CLOSING, ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.STREAMING,
                EnumSet.of(ProtocolConnectionState.READY, ProtocolConnectionState.CLOSING,
                        ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.CLOSING,
                EnumSet.of(ProtocolConnectionState.CLOSED));
        ALLOWED_TRANSITIONS.put(ProtocolConnectionState.CLOSED,
                EnumSet.noneOf(ProtocolConnectionState.class));
    }

    private final String protocolName;
    private final String connectionId;
    private final Map<String, Object> attributes = new HashMap<>();
    private ProtocolConnectionState state = ProtocolConnectionState.CONNECTED;
    private ObservationConfidence observationConfidence = ObservationConfidence.CONFIRMED;
    private final Instant connectedAt = Instant.now();
    private volatile Instant lastActivity = connectedAt;
    /** True while a transaction is open; set by protocol sessions from wire state. */
    private boolean inTransaction;
    /** Server-side prepared statements this session currently holds. */
    private int openPreparedStatements;
    private boolean hasSessionSettings;
    private boolean hasTemporaryObjects;
    private boolean hasUserVariables;
    private boolean hasLocks;
    private boolean tooComplexToReset;

    public ProtocolSession(String protocolName, String connectionId) {
        this.protocolName = protocolName;
        this.connectionId = connectionId;
    }

    public String getProtocolName() {
        return protocolName;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public ProtocolConnectionState getState() {
        return state;
    }

    public void transitionTo(ProtocolConnectionState nextState) {
        Set<ProtocolConnectionState> allowed = ALLOWED_TRANSITIONS.getOrDefault(state, Set.of());
        if (!allowed.contains(nextState)) {
            throw new ProtocolException("Illegal protocol state transition: " + state + " -> " + nextState);
        }
        this.state = nextState;
        touch();
    }

    /**
     * Attempts a state transition without failing the caller.
     *
     * <p>Transparent proxies advance state from observed cleartext frames. When
     * an observation is ambiguous the transition must never break byte
     * forwarding, so illegal transitions are reported as {@code false} instead
     * of throwing.</p>
     *
     * @return {@code true} when the transition was legal and applied
     */
    public boolean tryTransitionTo(ProtocolConnectionState nextState) {
        try {
            transitionTo(nextState);
            return true;
        } catch (ProtocolException e) {
            return false;
        }
    }

    /**
     * Moves the session to {@link ProtocolConnectionState#CLOSED} without throwing.
     */
    public void close() {
        if (state == ProtocolConnectionState.CLOSED) {
            return;
        }
        if (state != ProtocolConnectionState.CLOSING) {
            tryTransitionTo(ProtocolConnectionState.CLOSING);
        }
        tryTransitionTo(ProtocolConnectionState.CLOSED);
    }

    /**
     * How much the observed state of this session can be trusted (rule 2.10).
     */
    public ObservationConfidence getObservationConfidence() {
        return observationConfidence;
    }

    /**
     * True only while observation is known to match the protocol. Routing,
     * connection pooling and security decisions must require this.
     */
    public boolean isObservationTrusted() {
        return observationConfidence == ObservationConfidence.CONFIRMED;
    }

    /**
     * Returns observation to {@code CONFIRMED}. Called at a protocol resync point,
     * where the wire gives a boundary the observer can trust.
     */
    public void confirmObservation() {
        this.observationConfidence = ObservationConfidence.CONFIRMED;
    }

    /**
     * Degrades observation to {@code UNCERTAIN} without stopping it. A suspended
     * session is never upgraded by this call.
     */
    public void markObservationUncertain() {
        if (observationConfidence == ObservationConfidence.SUSPENDED) {
            return;
        }
        this.observationConfidence = ObservationConfidence.UNCERTAIN;
    }

    /**
     * Stops publishing session state until the protocol resynchronises. Used when
     * the observer cannot interpret the stream and must not guess.
     */
    public void suspendObservation() {
        this.observationConfidence = ObservationConfidence.SUSPENDED;
    }

    /** When the gateway accepted this connection. */
    public Instant getConnectedAt() {
        return connectedAt;
    }

    /** When this session last observed a state change. */
    public Instant getLastActivity() {
        return lastActivity;
    }

    /** True while a transaction is open on this session. */
    public boolean isInTransaction() {
        return inTransaction;
    }

    /**
     * Records the client-visible transaction state. Protocol sessions call this
     * from the wire status they observe, never from a hard-coded value (rule 2.7).
     */
    public void setInTransaction(boolean inTransaction) {
        this.inTransaction = inTransaction;
        touch();
    }

    /** Number of server-side prepared statements this session holds. */
    public int getOpenPreparedStatements() {
        return openPreparedStatements;
    }

    public void markPreparedStatementOpened() {
        openPreparedStatements++;
        touch();
    }

    /**
     * Records a closed prepared statement. Closing an unknown statement cannot
     * drive the count below zero.
     */
    public void markPreparedStatementClosed() {
        if (openPreparedStatements > 0) {
            openPreparedStatements--;
        }
        touch();
    }

    /** Records that every prepared statement of this session was dropped. */
    public void clearPreparedStatements() {
        openPreparedStatements = 0;
        touch();
    }

    /**
     * Records the session-level effects of an observed statement.
     *
     * <p>Effects that only describe data access (read, write, DDL, transaction
     * control) leave no reusable state behind and are ignored here. Everything
     * that would leak into another client marks the session dirty, and an
     * unclassifiable statement marks it as not provably clean (rule 8.3).</p>
     */
    public void recordStatementEffects(Collection<StatementEffect> effects) {
        if (effects != null) {
            for (StatementEffect effect : effects) {
                switch (effect) {
                    case SESSION_SETTING -> hasSessionSettings = true;
                    case TEMPORARY_OBJECT -> hasTemporaryObjects = true;
                    case USER_VARIABLE -> hasUserVariables = true;
                    case LOCK -> hasLocks = true;
                    case UNKNOWN -> tooComplexToReset = true;
                    case READ, WRITE, DDL, TRANSACTION_CONTROL -> {
                        // Data access leaves no session state to clean up.
                    }
                }
            }
        }
        touch();
    }

    /**
     * Session state that would leak into a later client of this connection.
     */
    public SessionDirtiness getDirtiness() {
        return new SessionDirtiness(openPreparedStatements > 0, hasSessionSettings, hasTemporaryObjects,
                hasUserVariables, hasLocks, tooComplexToReset);
    }

    /**
     * A consumable view of this session for auditing, risk control, routing and
     * connection pooling (rule 2.10/8.3).
     */
    public SessionSnapshot snapshot() {
        return new SessionSnapshot(protocolName, connectionId, state, observationConfidence, inTransaction,
                clientAttribute("client.user"), clientAttribute("client.database"),
                getDirtiness(), connectedAt, lastActivity);
    }

    private Optional<String> clientAttribute(String key) {
        Object value = attributes.get(key);
        return value instanceof String text ? Optional.of(text) : Optional.empty();
    }

    private void touch() {
        lastActivity = Instant.now();
    }

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
        touch();
    }

    public Optional<Object> getAttribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
