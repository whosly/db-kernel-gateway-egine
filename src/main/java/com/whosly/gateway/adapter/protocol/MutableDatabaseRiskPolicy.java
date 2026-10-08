package com.whosly.gateway.adapter.protocol;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hot-swappable {@link DatabaseRiskPolicy} shared across adapters.
 * {@link #evaluate} always reads the current delegate so PUT updates apply
 * to already-open sessions without restart.
 */
public final class MutableDatabaseRiskPolicy implements DatabaseRiskPolicy {

    private final AtomicReference<DatabaseRiskPolicy> delegate =
            new AtomicReference<>(DatabaseRiskPolicy.allowAll());

    public void replace(DatabaseRiskPolicy next) {
        delegate.set(next != null ? next : DatabaseRiskPolicy.allowAll());
    }

    public DatabaseRiskPolicy current() {
        return delegate.get();
    }

    @Override
    public RiskDecision evaluate(DatabaseTrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return delegate.get().evaluate(event);
    }
}
