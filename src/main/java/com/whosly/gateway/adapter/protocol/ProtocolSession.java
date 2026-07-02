package com.whosly.gateway.adapter.protocol;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-client protocol state shared by protocol-specific sessions.
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
    }

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Optional<Object> getAttribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
