package com.whosly.gateway.adapter.protocol;

import com.whosly.gateway.parser.SqlMasker;

import java.util.Objects;

/**
 * Masks literal values in observed SQL before it reaches an audit sink.
 *
 * <p>The gateway forwards the client's bytes unchanged, so this decorator never
 * touches the wire: it only changes what audit records. Wire it around the audit
 * sink, for example
 * {@code adapter.setDatabaseTrafficObserver(DatabaseTrafficObserver.masking(auditSink))},
 * so stored statements keep their structure but not the values a client sent
 * (rule 8.2).</p>
 *
 * <p>The risk policy is evaluated on the original event, not on the masked one,
 * because enforcement must see what the client actually submitted.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class MaskingTrafficObserver implements DatabaseTrafficObserver {

    private final DatabaseTrafficObserver delegate;
    private final SqlMasker masker;

    public MaskingTrafficObserver(DatabaseTrafficObserver delegate) {
        this(delegate, SqlMasker.standard());
    }

    public MaskingTrafficObserver(DatabaseTrafficObserver delegate, SqlMasker masker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.masker = Objects.requireNonNull(masker, "masker must not be null");
    }

    @Override
    public void onEvent(DatabaseTrafficEvent event) {
        delegate.onEvent(event.withStatement(masker.mask(event.getStatement())));
    }
}
