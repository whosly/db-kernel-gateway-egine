package com.whosly.gateway.adapter.protocol;

/**
 * Maps sanitized protocol or backend errors to protocol-native error messages.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@FunctionalInterface
public interface ProtocolErrorMapper {

    ProtocolMessage toErrorMessage(Throwable error);
}
