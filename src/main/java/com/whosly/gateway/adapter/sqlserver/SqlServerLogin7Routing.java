package com.whosly.gateway.adapter.sqlserver;

import com.whosly.gateway.adapter.protocol.RoutingContext;

/**
 * Maps Login7 identity into a {@link RoutingContext}.
 *
 * <p>Used by {@link SqlServerDeferredIdentityRouter} after a real PreLogin
 * round-trip with the bootstrap backend. PreLogin itself has no user/database,
 * so {@code probeClientForRouting} stays empty; identity selection happens in
 * the deferred phase (match → Login7 replay; mismatch → silent PreLogin rebind).
 * See {@code docs/SQLSERVER_TDS_PLAN.md} and scratch
 * {@code SQLSERVER_LOGIN7_IDENTITY_ROUTING_2026-10-23.md}.</p>
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
