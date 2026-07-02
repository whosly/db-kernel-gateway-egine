package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Bridges protocol SQL extraction, audit observation, and risk policy decisions.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public class SqlTrafficInspector implements TrafficInspector {

    private static final Logger log = LoggerFactory.getLogger(SqlTrafficInspector.class);

    private final SqlEventExtractor extractor;
    private final SqlTrafficObserver observer;
    private final SqlRiskPolicy riskPolicy;

    public SqlTrafficInspector(SqlEventExtractor extractor,
                               SqlTrafficObserver observer,
                               SqlRiskPolicy riskPolicy) {
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy must not be null");
    }

    @Override
    public TrafficAction onBytes(TrafficDirection direction, byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return TrafficAction.FORWARD;
        }

        List<SqlTrafficEvent> events = extractor.extract(direction, bytes, offset, length);
        for (SqlTrafficEvent event : events) {
            observer.onSql(event);
            RiskDecision decision = riskPolicy.evaluate(event);
            if (!decision.isAllowed()) {
                log.warn("SQL traffic denied for protocol {}, session {}, command {}: {}",
                        event.getProtocolName(), event.getSessionId(), event.getCommand(), decision.getReason());
                return TrafficAction.CLOSE;
            }
        }
        return TrafficAction.FORWARD;
    }
}
