package com.whosly.gateway.adapter.protocol;

import com.whosly.gateway.parser.StatementClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Bridges protocol event extraction, audit observation, and risk policy decisions.
 *
 * <p>Runs in the {@link InterceptorPhase#POLICY} phase, where it both records what
 * the client sent and enforces the risk policy on that original message. Rewrite
 * interceptors run after it by design, so a rewritten payload can never bypass
 * enforcement.</p>
 *
 * <p>The relay runs one thread per direction, so this class is the one place
 * where both directions of a session meet. Protocol state is therefore read and
 * written inside a single monitor: the extractor keeps one state machine per
 * connection, and letting two threads drive it would interleave the phases that
 * attribute a response to the command that produced it.</p>
 *
 * <p>The monitor covers protocol state only. Audit delivery and risk evaluation
 * run outside it, because a sink may be a file or a database: holding the monitor
 * across that I/O would let one direction's slow sink stall the other direction,
 * which rule 2.10 forbids.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-03
 */
public class DatabaseTrafficInspector implements MessageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTrafficInspector.class);

    private final DatabaseEventExtractor extractor;
    private final DatabaseTrafficObserver observer;
    private final DatabaseRiskPolicy riskPolicy;
    /** Optional session updated with the effects of the observed statements. */
    private final ProtocolSession session;
    /** Optional classifier used to derive those effects. */
    private final StatementClassifier statementClassifier;
    /**
     * Monitor guarding the protocol state shared by both relay directions.
     *
     * <p>It is the session when there is one, so that
     * {@link ProtocolSession#snapshot()} — the reader side — waits for the same
     * monitor and can never observe a half-applied protocol transition. A
     * deployment always has a session; tests that pass none fall back to the
     * extractor reference itself, which is still one monitor per inspector.</p>
     */
    private final Object stateMonitor;

    public DatabaseTrafficInspector(DatabaseEventExtractor extractor,
                                    DatabaseTrafficObserver observer,
                                    DatabaseRiskPolicy riskPolicy) {
        this(extractor, observer, riskPolicy, null, null);
    }

    /**
     * @param session             session to update with observed statement
     *                            effects; may be {@code null} in tests
     * @param statementClassifier classifier for those effects; may be
     *                            {@code null} to skip effect tracking
     */
    public DatabaseTrafficInspector(DatabaseEventExtractor extractor,
                                    DatabaseTrafficObserver observer,
                                    DatabaseRiskPolicy riskPolicy,
                                    ProtocolSession session,
                                    StatementClassifier statementClassifier) {
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy must not be null");
        this.session = session;
        this.statementClassifier = statementClassifier;
        this.stateMonitor = session != null ? session : extractor;
    }

    @Override
    public InterceptorPhase phase() {
        return InterceptorPhase.POLICY;
    }

    @Override
    public TrafficDecision intercept(WireMessage message) {
        if (message.originalLength() <= 0) {
            return TrafficDecision.forward(message);
        }

        List<DatabaseTrafficEvent> events = extractAndRecordState(message);
        /*
         * Audit and risk control deliberately run outside the monitor: the sink may
         * block on a file or a database, and holding the monitor across that I/O
         * would serialise the two directions and let a slow sink stall forwarding.
         */
        return dispatch(events, message);
    }

    /**
     * Drives the extractor and writes the session state it produces.
     *
     * <p>Both directions of the session call this, so it runs inside the
     * {@link #stateMonitor}: extraction and the session updates it triggers are one
     * atomic step, which is what keeps a response attributed to the command that
     * produced it.</p>
     */
    private List<DatabaseTrafficEvent> extractAndRecordState(WireMessage message) {
        synchronized (stateMonitor) {
            List<DatabaseTrafficEvent> events = extractor.extract(message.direction(),
                    message.originalBytes(), message.originalOffset(), message.originalLength());
            if (session != null && statementClassifier != null) {
                for (DatabaseTrafficEvent event : events) {
                    session.recordStatementEffects(statementClassifier.classify(event.getStatement()));
                }
            }
            return events;
        }
    }

    /**
     * Delivers the observed events to the audit sink and enforces the risk policy
     * on the original message.
     *
     * <p>A denial stops the chain but never suppresses the events that were already
     * recorded: an operation the gateway refused is exactly the one an audit trail
     * must contain.</p>
     */
    private TrafficDecision dispatch(List<DatabaseTrafficEvent> events, WireMessage message) {
        for (DatabaseTrafficEvent event : events) {
            if (!deliver(event)) {
                return TrafficDecision.deny(message);
            }
            RiskDecision decision = riskPolicy.evaluate(event);
            if (!decision.isAllowed()) {
                log.warn("Database traffic denied for protocol {}, session {}, operation {}: {}",
                        event.getProtocolName(), event.getSessionId(), event.getOperation(), decision.getReason());
                return TrafficDecision.deny(message);
            }
        }
        return TrafficDecision.forward(message);
    }

    /**
     * Delivers one event, applying the sink's failure policy.
     *
     * <p>A best-effort sink (dashboards, metrics) is fail-open: losing an event must
     * never fail a client connection. A mandatory sink — an audit trail — is
     * fail-closed: an operation the gateway cannot record must not run, otherwise
     * the trail is complete by claim and incomplete in fact (rule 8.5).</p>
     *
     * @return {@code false} when the operation must be denied
     */
    private boolean deliver(DatabaseTrafficEvent event) {
        try {
            observer.onEvent(event);
            return true;
        } catch (RuntimeException failure) {
            if (!observer.isDeliveryMandatory()) {
                log.warn("Audit sink failed for session {}, continuing (fail-open): {}",
                        event.getSessionId(), failure.toString());
                return true;
            }
            log.error("Audit sink could not record session {} operation {}; denying the operation (fail-closed)",
                    event.getSessionId(), event.getOperation(), failure);
            return false;
        }
    }
}
