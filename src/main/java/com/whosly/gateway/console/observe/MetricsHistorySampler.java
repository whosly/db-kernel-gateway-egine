package com.whosly.gateway.console.observe;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.console.GatewayInstance;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process ring of metrics snapshots for console sparklines (E lite).
 * Restart clears history; not a substitute for Prometheus.
 */
@Component
public class MetricsHistorySampler {

    public static final String OVERVIEW_KEY = "overview";

    private static final Logger log = LoggerFactory.getLogger(MetricsHistorySampler.class);

    private final GatewayInstanceRegistry instanceRegistry;
    private final GatewayListenerRuntime listenerRuntime;
    private final int intervalSeconds;
    private final int capacity;

    private final ConcurrentHashMap<String, Ring> rings = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> afterSampleListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    public MetricsHistorySampler(GatewayInstanceRegistry instanceRegistry,
                                 GatewayListenerRuntime listenerRuntime,
                                 @Value("${gateway.console.metrics-history.interval-seconds:5}") int intervalSeconds,
                                 @Value("${gateway.console.metrics-history.capacity:120}") int capacity) {
        this.instanceRegistry = Objects.requireNonNull(instanceRegistry, "instanceRegistry");
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.capacity = Math.max(10, Math.min(capacity, 2000));
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-history-sampler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sampleSafely, 1, intervalSeconds, TimeUnit.SECONDS);
        log.info("MetricsHistorySampler started (interval={}s capacity={})", intervalSeconds, capacity);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    public int capacity() {
        return capacity;
    }

    /** Snapshot history for one instance or overview (null/blank → overview). */
    public List<Map<String, Object>> history(String instanceId, int limit) {
        String key = (instanceId == null || instanceId.isBlank()) ? OVERVIEW_KEY : instanceId.trim();
        Ring ring = rings.get(key);
        if (ring == null) {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, capacity));
        return ring.recent(lim);
    }

    /** Test / manual trigger. */
    public void sampleNow() {
        sampleSafely();
    }

    /** Register a callback invoked after each successful sample (e.g. alert evaluation). */
    public void addAfterSampleListener(Runnable listener) {
        if (listener != null) {
            afterSampleListeners.add(listener);
        }
    }

    private void sampleSafely() {
        try {
            sample();
            for (Runnable r : afterSampleListeners) {
                try {
                    r.run();
                } catch (RuntimeException ex) {
                    log.debug("After-sample listener failed: {}", ex.toString());
                }
            }
        } catch (RuntimeException e) {
            log.debug("Metrics sample skipped: {}", e.toString());
        }
    }

    private void sample() {
        long t = System.currentTimeMillis();
        Map<String, Long> overview = new LinkedHashMap<>();
        long overviewActive = 0L;

        for (GatewayInstance instance : instanceRegistry.listInstances()) {
            String id = instance.id();
            GatewayRuntimeMetrics metrics = listenerRuntime.getMetrics(id).orElse(null);
            if (metrics == null) {
                metrics = GatewayRuntimeMetrics.noop();
            }
            Map<String, Long> snap = new LinkedHashMap<>(metrics.snapshot());
            long active = 0L;
            ProtocolAdapter adapter = listenerRuntime.getAdapter(id).orElse(null);
            if (adapter instanceof AbstractProtocolAdapter abs) {
                active = abs.getActiveConnectionCount();
            } else if (instance.activeConnections() != null) {
                active = instance.activeConnections();
            }
            snap.put("activeConnections", active);
            rings.computeIfAbsent(id, k -> new Ring(capacity)).add(point(t, snap));

            for (Map.Entry<String, Long> e : snap.entrySet()) {
                if ("activeConnections".equals(e.getKey())) {
                    continue;
                }
                overview.merge(e.getKey(), e.getValue(), Long::sum);
            }
            overviewActive += active;
        }
        overview.put("activeConnections", overviewActive);
        rings.computeIfAbsent(OVERVIEW_KEY, k -> new Ring(capacity)).add(point(t, overview));
    }

    private static Map<String, Object> point(long t, Map<String, Long> counters) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("t", t);
        for (Map.Entry<String, Long> e : counters.entrySet()) {
            p.put(e.getKey(), e.getValue());
        }
        return p;
    }

    private static final class Ring {
        private final int capacity;
        private final Map<String, Object>[] buf;
        private final AtomicInteger write = new AtomicInteger(0);
        private final AtomicInteger size = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        Ring(int capacity) {
            this.capacity = capacity;
            this.buf = new Map[capacity];
        }

        synchronized void add(Map<String, Object> point) {
            int i = write.getAndIncrement() % capacity;
            if (i < 0) {
                i = (i % capacity + capacity) % capacity;
            }
            buf[i] = point;
            size.updateAndGet(s -> Math.min(capacity, s + 1));
            if (write.get() > capacity * 1000) {
                write.set(write.get() % capacity);
            }
        }

        synchronized List<Map<String, Object>> recent(int limit) {
            int n = size.get();
            if (n == 0) {
                return List.of();
            }
            int take = Math.min(limit, n);
            List<Map<String, Object>> out = new ArrayList<>(take);
            int w = write.get();
            // oldest of the last `take` points
            for (int k = take; k >= 1; k--) {
                int idx = Math.floorMod(w - k, capacity);
                Map<String, Object> p = buf[idx];
                if (p != null) {
                    out.add(Map.copyOf(p));
                }
            }
            return out;
        }
    }
}
