package com.whosly.gateway.adapter.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Protocol-agnostic identity used to select a backend route.
 *
 * <p>Adapters fill this from connection metadata they already know (for example a
 * PostgreSQL StartupMessage user/database, or a confirmed {@link SessionSnapshot}).
 * MySQL, PostgreSQL, and future Oracle / SQL Server adapters reuse the same type —
 * nothing here is wire-format specific.</p>
 *
 * <p>Only confirmed / adapter-supplied values should be used for routing decisions
 * (see repository transparency rule on observation confidence).</p>
 */
public final class RoutingContext {

    private final Optional<String> username;
    private final Optional<String> database;

    private RoutingContext(Optional<String> username, Optional<String> database) {
        this.username = Objects.requireNonNull(username, "username");
        this.database = Objects.requireNonNull(database, "database");
    }

    public static RoutingContext empty() {
        return new RoutingContext(Optional.empty(), Optional.empty());
    }

    public static RoutingContext of(String username, String database) {
        return new RoutingContext(normalize(username), normalize(database));
    }

    public static RoutingContext ofUsername(String username) {
        return new RoutingContext(normalize(username), Optional.empty());
    }

    public static RoutingContext ofDatabase(String database) {
        return new RoutingContext(Optional.empty(), normalize(database));
    }

    /**
     * Builds a context from a session snapshot. Empty when observation is not
     * {@link ObservationConfidence#CONFIRMED} so untrusted identity is never routed on.
     */
    public static RoutingContext fromSnapshot(SessionSnapshot snapshot) {
        if (snapshot == null || !snapshot.isObservationTrusted()) {
            return empty();
        }
        return new RoutingContext(
                snapshot.clientUser().flatMap(RoutingContext::normalize),
                snapshot.clientDatabase().flatMap(RoutingContext::normalize));
    }

    public Optional<String> username() {
        return username;
    }

    public Optional<String> database() {
        return database;
    }

    public boolean isEmpty() {
        return username.isEmpty() && database.isEmpty();
    }

    private static Optional<String> normalize(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoutingContext that)) {
            return false;
        }
        return username.equals(that.username) && database.equals(that.database);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, database);
    }

    @Override
    public String toString() {
        return "RoutingContext{user=" + username.orElse("-") + ", db=" + database.orElse("-") + '}';
    }
}
