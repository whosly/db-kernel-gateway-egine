package com.whosly.gateway.adapter.protocol;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One config-driven route: match by database and/or username, then a weighted
 * endpoint list. Matching is case-insensitive; when both matchers are set they
 * combine with AND. At least one matcher is required.
 */
public final class RoutingRule {

    private final Optional<String> matchDatabase;
    private final Optional<String> matchUsername;
    private final List<WeightedEndpoint> endpoints;

    public RoutingRule(String matchDatabase, String matchUsername, List<WeightedEndpoint> endpoints) {
        Optional<String> db = normalizeMatcher(matchDatabase);
        Optional<String> user = normalizeMatcher(matchUsername);
        if (db.isEmpty() && user.isEmpty()) {
            throw new IllegalArgumentException(
                    "routing rule requires match-database and/or match-username");
        }
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("routing rule endpoints must not be empty");
        }
        this.matchDatabase = db;
        this.matchUsername = user;
        this.endpoints = List.copyOf(endpoints);
    }

    public Optional<String> matchDatabase() {
        return matchDatabase;
    }

    public Optional<String> matchUsername() {
        return matchUsername;
    }

    public List<WeightedEndpoint> endpoints() {
        return endpoints;
    }

    /**
     * True when every configured matcher agrees with the context (AND).
     * Missing context values fail the corresponding matcher.
     */
    public boolean matches(RoutingContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (matchDatabase.isPresent()) {
            String actual = context.database().orElse(null);
            if (actual == null || !matchDatabase.get().equalsIgnoreCase(actual)) {
                return false;
            }
        }
        if (matchUsername.isPresent()) {
            String actual = context.username().orElse(null);
            if (actual == null || !matchUsername.get().equalsIgnoreCase(actual)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> normalizeMatcher(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(trimmed.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return "RoutingRule{db=" + matchDatabase.orElse("*")
                + ", user=" + matchUsername.orElse("*")
                + ", endpoints=" + endpoints + '}';
    }
}
