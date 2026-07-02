package com.whosly.gateway.adapter.protocol;

/**
 * Protocol-level connection states shared by database wire protocols.
 */
public enum ProtocolConnectionState {
    CONNECTED,
    NEGOTIATING,
    AUTHENTICATING,
    READY,
    EXECUTING,
    STREAMING,
    CLOSING,
    CLOSED
}
