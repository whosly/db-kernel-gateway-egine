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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/**
 * Like {@link FailoverBackendProvider}, but chooses the first endpoint to try by
 * weight on each {@link #acquire()}. Remaining endpoints follow in config order
 * for failover. Unhealthy cooldown behaviour matches failover.
 */
public final class WeightedFailoverBackendProvider implements BackendProvider {

    private static final Logger log = LoggerFactory.getLogger(WeightedFailoverBackendProvider.class);

    private final List<WeightedEndpoint> endpoints;
    private final int connectTimeoutMillis;
    private final GatewayRuntimeMetrics metrics;
    private final long unhealthyCooldownMillis;
    private final ConcurrentHashMap<BackendEndpoint, Long> unhealthyUntilMillis = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final IntUnaryOperator nextIntBound;

    public WeightedFailoverBackendProvider(List<WeightedEndpoint> endpoints, int connectTimeoutMillis) {
        this(endpoints, connectTimeoutMillis, GatewayRuntimeMetrics.noop(),
                FailoverBackendProvider.DEFAULT_UNHEALTHY_COOLDOWN_MILLIS,
                System::currentTimeMillis, ThreadLocalRandom.current()::nextInt);
    }

    public WeightedFailoverBackendProvider(List<WeightedEndpoint> endpoints, int connectTimeoutMillis,
                                           GatewayRuntimeMetrics metrics) {
        this(endpoints, connectTimeoutMillis, metrics,
                FailoverBackendProvider.DEFAULT_UNHEALTHY_COOLDOWN_MILLIS,
                System::currentTimeMillis, ThreadLocalRandom.current()::nextInt);
    }

    WeightedFailoverBackendProvider(List<WeightedEndpoint> endpoints, int connectTimeoutMillis,
                                    GatewayRuntimeMetrics metrics, long unhealthyCooldownMillis,
                                    LongSupplier clock, IntUnaryOperator nextIntBound) {
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
        this.nextIntBound = Objects.requireNonNull(nextIntBound, "nextIntBound must not be null");
    }

    @Override
    public Socket acquire() throws IOException {
        long now = clock.getAsLong();
        List<BackendEndpoint> weightedOrder = WeightedEndpointSelector.orderForAttempt(endpoints, nextIntBound);
        List<BackendEndpoint> preferred = new ArrayList<>(weightedOrder.size());
        List<BackendEndpoint> deferred = new ArrayList<>();
        for (BackendEndpoint endpoint : weightedOrder) {
            if (isUnhealthy(endpoint, now)) {
                deferred.add(endpoint);
            } else {
                preferred.add(endpoint);
            }
        }
        List<BackendEndpoint> order = preferred.isEmpty() ? weightedOrder : preferred;
        if (!preferred.isEmpty() && !deferred.isEmpty()) {
            log.debug("Skipping {} unhealthy backend(s) while {} healthy candidate(s) remain",
                    deferred.size(), preferred.size());
        }

        BackendEndpoint firstConfigured = endpoints.get(0).endpoint();
        List<IOException> failures = new ArrayList<>();
        for (BackendEndpoint endpoint : order) {
            try {
                Socket socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), connectTimeoutMillis);
                unhealthyUntilMillis.remove(endpoint);
                if (!endpoint.equals(firstConfigured)) {
                    metrics.recordBackendFailover();
                    log.info("Connected to weighted/failover backend {} after skipping/failing earlier endpoint(s)",
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

    public List<WeightedEndpoint> endpoints() {
        return endpoints;
    }
}
