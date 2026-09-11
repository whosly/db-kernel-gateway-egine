package com.whosly.gateway.adapter.protocol;

/**
 * Action requested by a traffic inspector before bytes are forwarded.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public enum TrafficAction {

    /** Forward the bytes unchanged. */
    FORWARD,

    /**
     * Deny the operation: answer the client with a protocol-native error and
     * then close the connection.
     */
    DENY,

    /** Close the connection immediately, without a protocol response. */
    CLOSE
}
