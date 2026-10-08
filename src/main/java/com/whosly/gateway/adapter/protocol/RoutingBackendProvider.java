package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-agnostic decorator that selects a backend provider by
 * {@link RoutingContext} (database name and/or username).
 *
 * <p>Composition (outer → inner): {@code Routing → Pool → Failover/Fixed}.
 * First matching rule wins; when nothing matches (or context is empty), the
 * fallback provider is used — typically the existing target +
 * {@code gateway.backend-endpoints} failover list.</p>
 *
 * <p>Oracle / SQL Server adapters reuse this class unchanged once they fill a
 * {@link RoutingContext}.</p>
 */
public final class RoutingBackendProvider implements BackendProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RoutingBackendProvider.class);

    private final List<BoundRule> rules;
    private final BackendProvider fallback;

    public RoutingBackendProvider(List<BoundRule> rules, BackendProvider fallback) {
        Objects.requireNonNull(rules, "rules must not be null");
        this.rules = List.copyOf(rules);
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    public static BoundRule bind(RoutingRule rule, BackendProvider provider) {
        return new BoundRule(rule, provider);
    }

    @Override
    public Socket acquire() throws IOException {
        return fallback.acquire();
    }

    @Override
    public Socket acquire(RoutingContext context) throws IOException {
        Objects.requireNonNull(context, "context must not be null");
        for (BoundRule bound : rules) {
            if (bound.rule().matches(context)) {
                log.debug("Routing {} via rule {}", context, bound.rule());
                return bound.provider().acquire(context);
            }
        }
        log.debug("Routing {} via fallback (no rule matched)", context);
        return fallback.acquire(context);
    }

    @Override
    public void release(Socket connection) {
        // Without a route key we cannot return to the correct pool — fail-closed close.
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void release(Socket connection, SessionSnapshot snapshot) {
        if (connection == null) {
            return;
        }
        // Prefer the provider that would handle this identity so pooled routes
        // can reuse; if no rule matches, fall back.
        RoutingContext context = RoutingContext.fromSnapshot(snapshot);
        for (BoundRule bound : rules) {
            if (bound.rule().matches(context)) {
                bound.provider().release(connection, snapshot);
                return;
            }
        }
        fallback.release(connection, snapshot);
    }

    public BackendProvider fallback() {
        return fallback;
    }

    public List<BoundRule> rules() {
        return rules;
    }

    /** Sum of idle sockets across pooled fallback + rule providers (0 if none pooled). */
    public int idleCountHint() {
        int n = 0;
        if (fallback instanceof PooledBackendProvider pooled) {
            n += pooled.idleCount();
        }
        for (BoundRule bound : rules) {
            if (bound.provider() instanceof PooledBackendProvider pooled) {
                n += pooled.idleCount();
            }
        }
        return n;
    }

    @Override
    public void close() {
        closeQuietly(fallback);
        for (BoundRule bound : rules) {
            closeQuietly(bound.provider());
        }
    }

    private static void closeQuietly(BackendProvider provider) {
        if (provider instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** One matched rule and the (possibly pooled) provider for its endpoints. */
    public record BoundRule(RoutingRule rule, BackendProvider provider) {
        public BoundRule {
            Objects.requireNonNull(rule, "rule must not be null");
            Objects.requireNonNull(provider, "provider must not be null");
        }
    }

    /** Builder used by {@link com.whosly.gateway.adapter.AbstractProtocolAdapter}. */
    public static final class Builder {
        private final List<BoundRule> rules = new ArrayList<>();
        private BackendProvider fallback;

        public Builder fallback(BackendProvider fallback) {
            this.fallback = fallback;
            return this;
        }

        public Builder rule(RoutingRule rule, BackendProvider provider) {
            rules.add(bind(rule, provider));
            return this;
        }

        public RoutingBackendProvider build() {
            return new RoutingBackendProvider(rules, fallback);
        }
    }
}
