package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.RoutingContext;

/**
 * Maps Login7 identity into a {@link RoutingContext}.
 *
 * <p><b>Why the transparent adapter does not call this before
 * {@code acquire}:</b> TDS is client-first, but the first message is PreLogin
 * (no user/database). Login7 arrives only after a PreLogin round-trip with
 * <em>some</em> backend. Peeking Login7 before acquire would require forging a
 * PreLogin response (partial handshake termination) and is therefore out of
 * scope for the transparent duplex path. {@code SqlServerProtocolAdapter}
 * keeps {@code probeClientForRouting} empty and observes Login7 on the duplex
 * path only.</p>
 *
 * <p>This helper exists so tests and a future cleartext-terminating probe can
 * share one mapping without inventing a second identity model.</p>
 */
public final class SqlServerLogin7Routing {

    private SqlServerLogin7Routing() {
    }

    /**
     * Builds a routing context from a parsed Login7 observation.
     *
     * @return empty when observation is null or carries neither user nor database
     */
    public static RoutingContext fromObservation(Login7Observation observation) {
        if (observation == null) {
            return RoutingContext.empty();
        }
        String user = observation.username().orElse(null);
        String database = observation.database().orElse(null);
        if (user != null && database != null) {
            return RoutingContext.of(user, database);
        }
        if (user != null) {
            return RoutingContext.ofUsername(user);
        }
        if (database != null) {
            return RoutingContext.ofDatabase(database);
        }
        return RoutingContext.empty();
    }

    /**
     * Parses Login7 payload bytes and maps to {@link RoutingContext}.
     */
    public static RoutingContext fromLogin7Payload(byte[] payload) {
        return Login7Observation.tryParse(payload)
                .map(SqlServerLogin7Routing::fromObservation)
                .orElseGet(RoutingContext::empty);
    }
}
