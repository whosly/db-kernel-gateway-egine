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

        List<DatabaseTrafficEvent> events = extractor.extract(message.direction(),
                message.originalBytes(), message.originalOffset(), message.originalLength());
        for (DatabaseTrafficEvent event : events) {
            observer.onEvent(event);
            recordStatementEffects(event);
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
     * Feeds the observed statement into the session dirtiness model. This is
     * observation only: it never changes what is forwarded.
     */
    private void recordStatementEffects(DatabaseTrafficEvent event) {
        if (session == null || statementClassifier == null) {
            return;
        }
        session.recordStatementEffects(statementClassifier.classify(event.getStatement()));
    }
}
