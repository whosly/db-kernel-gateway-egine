package com.whosly.gateway.adapter.protocol;

import java.time.Instant;
import java.util.Optional;

/**
 * A consumable view of one observed session.
 *
 * <p>Auditing, risk control, routing and connection pooling all need the same
 * facts about a session, and all of them must know how much that observation can
 * be trusted. Publishing a snapshot keeps them from reaching into protocol
 * internals, and keeps "value correct" distinguishable from "value unknown"
 * (rule 2.10).</p>
 *
 * @param protocolName   protocol the session speaks
 * @param connectionId   gateway-side session identity
 * @param state          current protocol state
 * @param confidence     how much the observed state can be trusted
 * @param inTransaction  true while a transaction is open
 * @param clientUser     client identity observed on the wire, when known
 * @param clientDatabase default database observed on the wire, when known
 * @param dirtiness      session state that would leak into a later client
 * @param connectedAt    when the gateway accepted the connection
 * @param lastActivity   when the session last changed
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public record SessionSnapshot(
        String protocolName,
        String connectionId,
        ProtocolConnectionState state,
        ObservationConfidence confidence,
        boolean inTransaction,
        Optional<String> clientUser,
        Optional<String> clientDatabase,
        SessionDirtiness dirtiness,
        Instant connectedAt,
        Instant lastActivity) {

    /** True only while observation is known to match the protocol. */
    public boolean isObservationTrusted() {
        return confidence == ObservationConfidence.CONFIRMED;
    }

    /**
     * Whether another client could take over this connection without a reset.
     *
     * <p>Requires trusted observation, no open transaction and no session state.
     * This is the question connection pooling must ask before reuse (rule 8.3).</p>
     */
    public boolean isReusableWithoutReset() {
        return isObservationTrusted() && !inTransaction && dirtiness.isClean();
    }
}
