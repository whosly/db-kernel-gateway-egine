package com.whosly.gateway.adapter.protocol;

/**
 * Protocol-level connection states shared by database wire protocols.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
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
