package com.whosly.gateway.adapter.protocol;

/**
 * Sanitized protocol-layer exception.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
