package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fan-out {@link DatabaseTrafficObserver}: delivers each event to two sinks.
 */
final class CompositeDatabaseTrafficObserver implements DatabaseTrafficObserver {

    private static final Logger log = LoggerFactory.getLogger(CompositeDatabaseTrafficObserver.class);

    private final DatabaseTrafficObserver first;
    private final DatabaseTrafficObserver second;

    CompositeDatabaseTrafficObserver(DatabaseTrafficObserver first, DatabaseTrafficObserver second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public void onEvent(DatabaseTrafficEvent event) {
        deliver(first, event);
        deliver(second, event);
    }

    @Override
    public boolean isDeliveryMandatory() {
        return first.isDeliveryMandatory() || second.isDeliveryMandatory();
    }

    @Override
    public void onSessionClosed(String sessionId) {
        try {
            first.onSessionClosed(sessionId);
        } catch (RuntimeException e) {
            if (first.isDeliveryMandatory()) {
                throw e;
            }
            log.debug("Non-mandatory observer onSessionClosed failed: {}", e.getMessage());
        }
        try {
            second.onSessionClosed(sessionId);
        } catch (RuntimeException e) {
            if (second.isDeliveryMandatory()) {
                throw e;
            }
            log.debug("Non-mandatory observer onSessionClosed failed: {}", e.getMessage());
        }
    }

    private static void deliver(DatabaseTrafficObserver sink, DatabaseTrafficEvent event) {
        try {
            sink.onEvent(event);
        } catch (RuntimeException e) {
            if (sink.isDeliveryMandatory()) {
                throw e;
            }
            log.debug("Non-mandatory observer onEvent failed: {}", e.getMessage());
        }
    }
}
