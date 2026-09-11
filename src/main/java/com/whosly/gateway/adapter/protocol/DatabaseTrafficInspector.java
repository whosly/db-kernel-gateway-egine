package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Bridges protocol event extraction, audit observation, and risk policy decisions.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-03
 */
public class DatabaseTrafficInspector implements TrafficInspector {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTrafficInspector.class);

    private final DatabaseEventExtractor extractor;
    private final DatabaseTrafficObserver observer;
    private final DatabaseRiskPolicy riskPolicy;

    public DatabaseTrafficInspector(DatabaseEventExtractor extractor,
                                    DatabaseTrafficObserver observer,
                                    DatabaseRiskPolicy riskPolicy) {
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy must not be null");
    }

    @Override
    public TrafficAction onBytes(TrafficDirection direction, byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return TrafficAction.FORWARD;
        }

        List<DatabaseTrafficEvent> events = extractor.extract(direction, bytes, offset, length);
        for (DatabaseTrafficEvent event : events) {
            observer.onEvent(event);
            RiskDecision decision = riskPolicy.evaluate(event);
            if (!decision.isAllowed()) {
                log.warn("Database traffic denied for protocol {}, session {}, operation {}: {}",
                        event.getProtocolName(), event.getSessionId(), event.getOperation(), decision.getReason());
                return TrafficAction.CLOSE;
            }
        }
        return TrafficAction.FORWARD;
    }
}
