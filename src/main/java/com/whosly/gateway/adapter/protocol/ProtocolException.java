package com.whosly.gateway.adapter.protocol;

/**
 * Sanitized protocol-layer exception.
 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
