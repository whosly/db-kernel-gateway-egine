package com.whosly.gateway.audit;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.parser.StatementClassifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

public final class SpoolingTrafficObserver implements DatabaseTrafficObserver {
    private final AuditSpool spool;
    private final StatementClassifier classifier;
    public SpoolingTrafficObserver(AuditSpool spool, StatementClassifier classifier) {
        this.spool = Objects.requireNonNull(spool);
        this.classifier = classifier;
    }
    @Override public void onEvent(DatabaseTrafficEvent event) {
        try {
            if (classifier != null) classifier.classify(event.getStatement());
            spool.append(event);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append audit event", e);
        }
    }
    @Override public boolean isDeliveryMandatory() { return true; }
}
