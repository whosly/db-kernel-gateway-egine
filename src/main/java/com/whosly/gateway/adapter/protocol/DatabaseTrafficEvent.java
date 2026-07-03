package com.whosly.gateway.adapter.protocol;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Database operation observed from cleartext database client protocol traffic.
 *
 * <p>The operation is the protocol-level command name, such as COM_QUERY,
 * SQL_BATCH, RPC_REQUEST, or Redis GET. The statement is the human-readable
 * payload used by audit and risk controls, such as SQL text or a Redis command
 * summary.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-03
 */
public final class DatabaseTrafficEvent {

    private final String protocolName;
    private final String sessionId;
    private final String operation;
    private final String statement;
    private final Instant observedAt;
    private final Map<String, String> attributes;

    private DatabaseTrafficEvent(Builder builder) {
        this.protocolName = Objects.requireNonNull(builder.protocolName, "protocolName must not be null");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId must not be null");
        this.operation = Objects.requireNonNull(builder.operation, "operation must not be null");
        this.statement = Objects.requireNonNull(builder.statement, "statement must not be null");
        this.observedAt = Objects.requireNonNull(builder.observedAt, "observedAt must not be null");
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    public String getProtocolName() {
        return protocolName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getOperation() {
        return operation;
    }

    public String getStatement() {
        return statement;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public Optional<String> getAttribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    public static Builder builder(String protocolName, String sessionId, String operation, String statement) {
        return new Builder(protocolName, sessionId, operation, statement);
    }

    public static final class Builder {

        private final String protocolName;
        private final String sessionId;
        private final String operation;
        private final String statement;
        private Instant observedAt = Instant.now();
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(String protocolName, String sessionId, String operation, String statement) {
            this.protocolName = protocolName;
            this.sessionId = sessionId;
            this.operation = operation;
            this.statement = statement;
        }

        public Builder observedAt(Instant observedAt) {
            this.observedAt = observedAt;
            return this;
        }

        public Builder attribute(String name, String value) {
            if (value != null) {
                attributes.put(name, value);
            }
            return this;
        }

        public DatabaseTrafficEvent build() {
            return new DatabaseTrafficEvent(this);
        }
    }
}
