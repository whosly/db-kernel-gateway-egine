package com.whosly.gateway.adapter.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tries backend endpoints in order until one accepts the TCP connection.
 *
 * <p>Endpoints that recently failed a connect are skipped while a healthier
 * candidate remains, then retried after a short cooldown so a recovered host is
 * not permanently black-holed. There is still no per-request routing by database
 * name or username — only ordered failover with health-aware skip.</p>
 */
public final class FailoverBackendProvider implements BackendProvider {

    private static final Logger log = LoggerFactory.getLogger(FailoverBackendProvider.class);

    /** Default time a failed endpoint stays skipped when another candidate exists. */
    public static final long DEFAULT_UNHEALTHY_COOLDOWN_MILLIS = 30_000L;

    private final List<BackendEndpoint> endpoints;
    private final int connectTimeoutMillis;
    private final GatewayRuntimeMetrics metrics;
    private final long unhealthyCooldownMillis;
    private final ConcurrentHashMap<BackendEndpoint, Long> unhealthyUntilMillis = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public FailoverBackendProvider(List<BackendEndpoint> endpoints, int connectTimeoutMillis) {
        this(endpoints, connectTimeoutMillis, GatewayRuntimeMetrics.noop(), DEFAULT_UNHEALTHY_COOLDOWN_MILLIS,
                System::currentTimeMillis);
    }

    public FailoverBackendProvider(List<BackendEndpoint> endpoints, int connectTimeoutMillis,
                                   GatewayRuntimeMetrics metrics) {
        this(endpoints, connectTimeoutMillis, metrics, DEFAULT_UNHEALTHY_COOLDOWN_MILLIS, System::currentTimeMillis);
    }

    /**
     * Package-visible constructor for tests that control the clock and cooldown.
     */
    FailoverBackendProvider(List<BackendEndpoint> endpoints, int connectTimeoutMillis,
                            GatewayRuntimeMetrics metrics, long unhealthyCooldownMillis,
                            LongSupplier clock) {
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
        if (unhealthyCooldownMillis < 0) {
            throw new IllegalArgumentException("unhealthyCooldownMillis must not be negative");
        }
        this.endpoints = List.copyOf(endpoints);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.metrics = metrics != null ? metrics : GatewayRuntimeMetrics.noop();
        this.unhealthyCooldownMillis = unhealthyCooldownMillis;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Socket acquire() throws IOException {
        long now = clock.getAsLong();
        List<BackendEndpoint> preferred = new ArrayList<>(endpoints.size());
        List<BackendEndpoint> deferred = new ArrayList<>();
        for (BackendEndpoint endpoint : endpoints) {
            if (isUnhealthy(endpoint, now)) {
                deferred.add(endpoint);
            } else {
                preferred.add(endpoint);
            }
        }
        // Prefer healthy endpoints; if every endpoint is cooling down, try them anyway.
        List<BackendEndpoint> order = preferred.isEmpty() ? List.copyOf(endpoints) : preferred;
        if (!preferred.isEmpty() && !deferred.isEmpty()) {
            log.debug("Skipping {} unhealthy backend(s) while {} healthy candidate(s) remain",
                    deferred.size(), preferred.size());
        }

        List<IOException> failures = new ArrayList<>();
        for (BackendEndpoint endpoint : order) {
            try {
                Socket socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), connectTimeoutMillis);
                unhealthyUntilMillis.remove(endpoint);
                if (!endpoint.equals(endpoints.get(0))) {
                    metrics.recordBackendFailover();
                    log.info("Connected to failover backend {} after skipping/failing earlier endpoint(s)",
                            endpoint);
                }
                return socket;
            } catch (IOException e) {
                failures.add(e);
                markUnhealthy(endpoint, now);
                log.warn("Backend {} unreachable: {}", endpoint, e.getMessage());
            }
        }
        IOException aggregate = new IOException("All " + endpoints.size() + " backend endpoint(s) unreachable");
        for (IOException failure : failures) {
            aggregate.addSuppressed(failure);
        }
        throw aggregate;
    }

    private boolean isUnhealthy(BackendEndpoint endpoint, long now) {
        Long until = unhealthyUntilMillis.get(endpoint);
        if (until == null) {
            return false;
        }
        if (until <= now) {
            unhealthyUntilMillis.remove(endpoint, until);
            return false;
        }
        return true;
    }

    private void markUnhealthy(BackendEndpoint endpoint, long now) {
        if (unhealthyCooldownMillis == 0) {
            return;
        }
        unhealthyUntilMillis.put(endpoint, now + unhealthyCooldownMillis);
    }

    /** Test helper: whether an endpoint is currently marked unhealthy. */
    boolean isMarkedUnhealthy(BackendEndpoint endpoint) {
        return isUnhealthy(endpoint, clock.getAsLong());
    }

    @Override
    public void release(Socket connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException ignored) {
        }
    }

    public List<BackendEndpoint> endpoints() {
        return endpoints;
    }
}
