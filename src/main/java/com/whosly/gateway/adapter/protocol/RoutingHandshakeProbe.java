package com.whosly.gateway.adapter.protocol;

import java.io.IOException;
import java.net.Socket;

/**
 * Protocol-agnostic SPI: derive a {@link RoutingContext} from early client
 * handshake / login bytes before the backend socket is acquired.
 *
 * <p>Implementations live next to each wire adapter (PostgreSQL StartupMessage,
 * MySQL Handshake Response when deferred connect is possible, future Oracle /
 * TDS login). {@link com.whosly.gateway.adapter.AbstractProtocolAdapter} calls
 * the probe so routing rules see real identity instead of
 * {@link RoutingContext#empty()}.</p>
 */
@FunctionalInterface
public interface RoutingHandshakeProbe {

    /** Probe that never reads the client and always returns {@link ProbedHandshake#empty()}. */
    RoutingHandshakeProbe NONE = clientSocket -> ProbedHandshake.empty();

    /**
     * Peek / parse enough of the first client packets to fill a routing context.
     *
     * @param clientSocket accepted client connection (streams may be partially consumed)
     * @return context plus any consumed bytes that must be replayed to the backend
     */
    ProbedHandshake probe(Socket clientSocket) throws IOException;
}
