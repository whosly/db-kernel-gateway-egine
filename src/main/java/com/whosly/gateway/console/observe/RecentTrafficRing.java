package com.whosly.gateway.console.observe;

import com.whosly.gateway.adapter.protocol.DatabaseTrafficEvent;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory ring of recent observed statements for the console control plane.
 *
 * <p><b>Honest limits</b>: capacity-bounded, process-local, lost on restart.
 * Not a substitute for {@code gateway.audit} spool. Prefer wiring
 * <em>after</em> statement masking so literals already redacted when possible.</p>
 */
@Component
public final class RecentTrafficRing implements DatabaseTrafficObserver {

    public static final int DEFAULT_CAPACITY = 100;
    public static final int DEFAULT_STATEMENT_MAX = 512;

    private final int capacity;
    private final int statementMaxChars;
    private final RecentEntry[] buffer;
    private final ReentrantLock lock = new ReentrantLock();
    private int next;
    private int size;

    public RecentTrafficRing() {
        this(DEFAULT_CAPACITY, DEFAULT_STATEMENT_MAX);
    }

    public RecentTrafficRing(int capacity) {
        this(capacity, DEFAULT_STATEMENT_MAX);
    }

    public RecentTrafficRing(int capacity, int statementMaxChars) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (statementMaxChars <= 0) {
            throw new IllegalArgumentException("statementMaxChars must be positive");
        }
        this.capacity = capacity;
        this.statementMaxChars = statementMaxChars;
        this.buffer = new RecentEntry[capacity];
    }

    /**
     * Observer that stamps {@code instanceId} onto stored entries before recording.
     */
    public DatabaseTrafficObserver forInstance(String instanceId) {
        String id = Objects.requireNonNull(instanceId, "instanceId");
        return new DatabaseTrafficObserver() {
            @Override
            public void onEvent(DatabaseTrafficEvent event) {
                record(id, event);
            }

            @Override
            public void onSessionClosed(String sessionId) {
                // no per-session state
            }
        };
    }

    @Override
    public void onEvent(DatabaseTrafficEvent event) {
        record(null, event);
    }

    @Override
    public boolean isDeliveryMandatory() {
        return false;
    }

    public void record(String instanceId, DatabaseTrafficEvent event) {
        if (event == null) {
            return;
        }
        String stmt = truncate(event.getStatement());
        RecentEntry entry = new RecentEntry(
                instanceId,
                event.getSessionId(),
                event.getProtocolName(),
                event.getOperation(),
                stmt,
                event.getObservedAt() != null ? event.getObservedAt() : Instant.now());
        lock.lock();
        try {
            buffer[next] = entry;
            next = (next + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Newest-first entries, optionally filtered by instance id.
     */
    public List<RecentEntry> recent(String instanceId, int limit) {
        int lim = Math.max(0, Math.min(limit, capacity));
        List<RecentEntry> out = new ArrayList<>(lim);
        lock.lock();
        try {
            for (int i = 0; i < size && out.size() < lim; i++) {
                int idx = Math.floorMod(next - 1 - i, capacity);
                RecentEntry e = buffer[idx];
                if (e == null) {
                    continue;
                }
                if (instanceId != null && !instanceId.isBlank()
                        && (e.instanceId() == null || !instanceId.equals(e.instanceId()))) {
                    continue;
                }
                out.add(e);
            }
        } finally {
            lock.unlock();
        }
        return List.copyOf(out);
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    private String truncate(String statement) {
        if (statement == null) {
            return "";
        }
        if (statement.length() <= statementMaxChars) {
            return statement;
        }
        return statement.substring(0, statementMaxChars) + "…";
    }

    public record RecentEntry(
            String instanceId,
            String sessionId,
            String protocolName,
            String operation,
            String statement,
            Instant observedAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instanceId", instanceId);
            m.put("sessionId", sessionId);
            m.put("protocolName", protocolName);
            m.put("operation", operation);
            m.put("eventType", operation);
            m.put("statement", statement);
            m.put("observedAt", observedAt != null ? observedAt.toString() : null);
            return m;
        }
    }
}
