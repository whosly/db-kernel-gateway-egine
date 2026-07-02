package com.whosly.gateway.adapter.protocol;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL statement observed from cleartext database client protocol traffic.
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
public final class SqlTrafficEvent {

    private final String protocolName;
    private final String sessionId;
    private final String command;
    private final String sql;
    private final Instant observedAt;
    private final Map<String, String> attributes;

    private SqlTrafficEvent(Builder builder) {
        this.protocolName = Objects.requireNonNull(builder.protocolName, "protocolName must not be null");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId must not be null");
        this.command = Objects.requireNonNull(builder.command, "command must not be null");
        this.sql = Objects.requireNonNull(builder.sql, "sql must not be null");
        this.observedAt = Objects.requireNonNull(builder.observedAt, "observedAt must not be null");
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    public String getProtocolName() {
        return protocolName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCommand() {
        return command;
    }

    public String getSql() {
        return sql;
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

    public static Builder builder(String protocolName, String sessionId, String command, String sql) {
        return new Builder(protocolName, sessionId, command, sql);
    }

    public static final class Builder {

        private final String protocolName;
        private final String sessionId;
        private final String command;
        private final String sql;
        private Instant observedAt = Instant.now();
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(String protocolName, String sessionId, String command, String sql) {
            this.protocolName = protocolName;
            this.sessionId = sessionId;
            this.command = command;
            this.sql = sql;
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

        public SqlTrafficEvent build() {
            return new SqlTrafficEvent(this);
        }
    }
}
