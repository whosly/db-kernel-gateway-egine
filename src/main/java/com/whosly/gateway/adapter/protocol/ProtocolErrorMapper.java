package com.whosly.gateway.adapter.protocol;

/**
 * Maps sanitized protocol or backend errors to protocol-native error messages.
 */
@FunctionalInterface
public interface ProtocolErrorMapper {

    ProtocolMessage toErrorMessage(Throwable error);
}
