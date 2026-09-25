package com.whosly.gateway.console.observe;

import com.whosly.gateway.console.persist.AlertThresholdStore;
import com.whosly.gateway.console.persist.AlertThresholdStore.AlertThresholdRecord;
import com.whosly.gateway.console.security.ConsoleAuditService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process alert evaluation against {@link MetricsHistorySampler} rings.
 * Not Prometheus / PagerDuty — lab control-plane only.
 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    private static final Set<String> COMPARATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "CRITICAL");

    /** Canonical metric keys exposed to operators (snake_case preferred). */
    public static final List<String> METRIC_KEYS = List.of(
            "active_sessions",
            "deny_count",
            "backend_fail",
            "error_rate",
            "connections_accepted",
            "connections_rejected_limit",
            "connections_rejected_policy",
            "opaque_tunnels_denied",
            "opaque_tunnels_entered");

    private final AlertThresholdStore store;
    private final MetricsHistorySampler sampler;
    private final ConsoleAuditService auditService;

    /** thresholdId → current active alert view (in-memory; rebuilt on evaluate). */
    private final ConcurrentHashMap<String, Map<String, Object>> active = new ConcurrentHashMap<>();
    private volatile Instant lastEvaluatedAt;

    public AlertEvaluationService(AlertThresholdStore store,
                                  MetricsHistorySampler sampler,
                                  ObjectProvider<ConsoleAuditService> auditProvider) {
        this.store = Objects.requireNonNull(store, "store");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.auditService = auditProvider != null ? auditProvider.getIfAvailable() : null;
    }

    @PostConstruct
    public void bindToSampler() {
        sampler.addAfterSampleListener(this::evaluateSafely);
        log.info("AlertEvaluationService bound to MetricsHistorySampler tick");
    }

    public void evaluateSafely() {
        try {
            evaluate();
        } catch (RuntimeException e) {
            log.debug("Alert evaluate skipped: {}", e.toString());
        }
    }

    /** Full pass over enabled thresholds; updates H2 last_* and in-memory active map. */
    public synchronized List<Map<String, Object>> evaluate() {
        Instant now = Instant.now();
        List<AlertThresholdRecord> rules = store.findEnabled();
        List<Map<String, Object>> firing = new ArrayList<>();
        ConcurrentHashMap<String, Map<String, Object>> next = new ConcurrentHashMap<>();

        for (AlertThresholdRecord rule : rules) {
            ResolvedMetric resolved = resolveMetric(rule);
            if (resolved == null) {
                continue;
            }
            boolean isFiring = compare(rule.comparator(), resolved.value(), rule.thresholdValue());
            Instant firedAt = isFiring ? now : rule.lastFiredAt();
            store.updateEvalState(rule.id(), isFiring, resolved.value(), isFiring ? now : null);
            if (isFiring) {
                Map<String, Object> alert = toActiveView(rule, resolved, now);
                firing.add(alert);
                next.put(rule.id(), alert);
            }
        }
        active.clear();
        active.putAll(next);
        lastEvaluatedAt = now;
        return List.copyOf(firing);
    }

    public Map<String, Object> listActive(boolean refresh) {
        if (refresh || lastEvaluatedAt == null) {
            evaluate();
        }
        List<Map<String, Object>> items = new CopyOnWriteArrayList<>(active.values());
        items.sort((a, b) -> {
            int sev = severityRank(String.valueOf(b.get("severity")))
                    - severityRank(String.valueOf(a.get("severity")));
            if (sev != 0) return sev;
            return String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")));
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        body.put("evaluatedAt", lastEvaluatedAt != null ? lastEvaluatedAt.toString() : null);
        body.put("note", "进程内评估（MetricsHistorySampler tick + 本接口懒评估）；"
                + "H2 持久化阈值与 last_fired；重启后需再采样才刷新当前触发态；非 Prometheus / 非 PagerDuty");
        return body;
    }

    public Map<String, Object> listThresholds() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlertThresholdRecord r : store.findAll()) {
            items.add(toThresholdView(r));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        body.put("metricKeys", METRIC_KEYS);
        body.put("comparators", List.copyOf(COMPARATORS));
        body.put("severities", List.of("INFO", "WARN", "CRITICAL"));
        body.put("note", "阈值存 H2；评估用进程内 metrics 环；windowSeconds>0 时对计数器取窗口增量");
        return body;
    }

    public Map<String, Object> getThreshold(String id) {
        AlertThresholdRecord r = store.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警阈值不存在: " + id));
        return toThresholdView(r);
    }

    public Map<String, Object> create(ThresholdInput input) {
        AlertThresholdRecord validated = validateNew(input, null);
        AlertThresholdRecord saved = store.insert(validated);
        if (auditService != null) {
            auditService.record("alert-threshold.create", saved.instanceId(),
                    ConsoleAuditService.detail("id", saved.id(), "metric", saved.metricKey()));
        }
        evaluateSafely();
        return toThresholdView(store.findById(saved.id()).orElse(saved));
    }

    public Map<String, Object> update(String id, ThresholdInput input) {
        if (store.findById(id).isEmpty()) {
            throw new IllegalArgumentException("告警阈值不存在: " + id);
        }
        AlertThresholdRecord validated = validateNew(input, id);
        AlertThresholdRecord saved = store.update(validated);
        if (auditService != null) {
            auditService.record("alert-threshold.update", saved.instanceId(),
                    ConsoleAuditService.detail("id", saved.id(), "metric", saved.metricKey()));
        }
        evaluateSafely();
        return toThresholdView(store.findById(saved.id()).orElse(saved));
    }

    public Map<String, Object> delete(String id) {
        if (!store.delete(id)) {
            throw new IllegalArgumentException("告警阈值不存在: " + id);
        }
        active.remove(id);
        if (auditService != null) {
            auditService.record("alert-threshold.delete", null,
                    ConsoleAuditService.detail("id", id));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        body.put("message", "已删除告警阈值");
        return body;
    }

    public int activeCount() {
        return active.size();
    }

    private AlertThresholdRecord validateNew(ThresholdInput input, String existingId) {
        if (input == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String metric = normalizeMetricKey(input.metricKey());
        if (metric == null) {
            throw new IllegalArgumentException(
                    "不支持的 metricKey；可选: " + String.join(", ", METRIC_KEYS));
        }
        String cmp = input.comparator() == null ? "" : input.comparator().trim().toUpperCase(Locale.ROOT);
        if (!COMPARATORS.contains(cmp)) {
            throw new IllegalArgumentException("comparator 须为 GT/GTE/LT/LTE/EQ");
        }
        if (input.thresholdValue() == null || Double.isNaN(input.thresholdValue())
                || Double.isInfinite(input.thresholdValue())) {
            throw new IllegalArgumentException("thresholdValue 必须为有效数字");
        }
        String sev = input.severity() == null || input.severity().isBlank()
                ? "WARN"
                : input.severity().trim().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(sev)) {
            throw new IllegalArgumentException("severity 须为 INFO/WARN/CRITICAL");
        }
        String name = input.name() == null || input.name().isBlank()
                ? metric + " " + cmp + " " + input.thresholdValue()
                : input.name().trim();
        if (name.length() > 256) {
            throw new IllegalArgumentException("name 过长");
        }
        Integer window = input.windowSeconds();
        if (window != null && window < 0) {
            throw new IllegalArgumentException("windowSeconds 不能为负");
        }
        if (window != null && window == 0) {
            window = null;
        }
        String instanceId = input.instanceId() == null || input.instanceId().isBlank()
                ? null
                : input.instanceId().trim();
        boolean enabled = input.enabled() == null || input.enabled();
        Instant now = Instant.now();
        Optional<AlertThresholdRecord> prev = existingId != null ? store.findById(existingId) : Optional.empty();
        return new AlertThresholdRecord(
                existingId != null ? existingId : null,
                name,
                metric,
                cmp,
                input.thresholdValue(),
                window,
                instanceId,
                enabled,
                sev,
                prev.map(AlertThresholdRecord::lastFiredAt).orElse(null),
                prev.map(AlertThresholdRecord::lastValue).orElse(null),
                prev.map(AlertThresholdRecord::lastFiring).orElse(false),
                prev.map(AlertThresholdRecord::createdAt).orElse(now),
                now);
    }

    static String normalizeMetricKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String k = raw.trim();
        String lower = k.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (lower) {
            case "active_sessions", "activesessions", "active_connections", "activeconnections" ->
                    "active_sessions";
            case "deny_count", "policy_denials", "policydenials" -> "deny_count";
            case "backend_fail", "backend_failovers", "backendfailovers" -> "backend_fail";
            case "error_rate", "errorrate" -> "error_rate";
            case "connections_accepted", "connectionsaccepted" -> "connections_accepted";
            case "connections_rejected_limit", "connectionsrejectedlimit" ->
                    "connections_rejected_limit";
            case "connections_rejected_policy", "connectionsrejectedpolicy" ->
                    "connections_rejected_policy";
            case "opaque_tunnels_denied", "opaquetunnelsdenied" -> "opaque_tunnels_denied";
            case "opaque_tunnels_entered", "opaquetunnelsentered" -> "opaque_tunnels_entered";
            default -> METRIC_KEYS.contains(lower) ? lower : null;
        };
    }

    private ResolvedMetric resolveMetric(AlertThresholdRecord rule) {
        String scopeKey = rule.instanceId() == null || rule.instanceId().isBlank()
                ? null
                : rule.instanceId();
        int window = rule.windowSeconds() != null ? Math.max(0, rule.windowSeconds()) : 0;
        // Need enough points: ~ window/interval + 2; sampler default 5s
        int need = window > 0
                ? Math.min(sampler.capacity(), Math.max(3, window / Math.max(1, sampler.intervalSeconds()) + 3))
                : 2;
        List<Map<String, Object>> hist = sampler.history(scopeKey, need);
        if (hist.isEmpty()) {
            return null;
        }
        Map<String, Object> latest = hist.get(hist.size() - 1);
        double current = extractValue(rule.metricKey(), latest);
        boolean gauge = "active_sessions".equals(rule.metricKey());
        double value;
        String mode;
        if (gauge || window <= 0) {
            value = current;
            mode = gauge ? "gauge" : "absolute";
        } else {
            long latestT = asLong(latest.get("t"));
            long targetT = latestT - window * 1000L;
            Map<String, Object> baseline = hist.get(0);
            for (Map<String, Object> p : hist) {
                long t = asLong(p.get("t"));
                if (t <= targetT) {
                    baseline = p;
                }
            }
            double base = extractValue(rule.metricKey(), baseline);
            value = Math.max(0, current - base);
            mode = "window_delta";
        }
        return new ResolvedMetric(value, mode, scopeKey == null ? MetricsHistorySampler.OVERVIEW_KEY : scopeKey);
    }

    static double extractValue(String metricKey, Map<String, Object> point) {
        return switch (metricKey) {
            case "active_sessions" -> asDouble(point.get("activeConnections"));
            case "deny_count" -> asDouble(point.get("policyDenials"));
            case "backend_fail" -> asDouble(point.get("backendFailovers"));
            case "connections_accepted" -> asDouble(point.get("connectionsAccepted"));
            case "connections_rejected_limit" -> asDouble(point.get("connectionsRejectedLimit"));
            case "connections_rejected_policy" -> asDouble(point.get("connectionsRejectedPolicy"));
            case "opaque_tunnels_denied" -> asDouble(point.get("opaqueTunnelsDenied"));
            case "opaque_tunnels_entered" -> asDouble(point.get("opaqueTunnelsEntered"));
            case "error_rate" -> asDouble(point.get("policyDenials"))
                    + asDouble(point.get("connectionsRejectedLimit"))
                    + asDouble(point.get("connectionsRejectedPolicy"))
                    + asDouble(point.get("opaqueTunnelsDenied"));
            default -> 0d;
        };
    }

    static boolean compare(String comparator, double actual, double threshold) {
        return switch (comparator) {
            case "GT" -> actual > threshold;
            case "GTE" -> actual >= threshold;
            case "LT" -> actual < threshold;
            case "LTE" -> actual <= threshold;
            case "EQ" -> Double.compare(actual, threshold) == 0;
            default -> false;
        };
    }

    private static Map<String, Object> toThresholdView(AlertThresholdRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("metricKey", r.metricKey());
        m.put("comparator", r.comparator());
        m.put("thresholdValue", r.thresholdValue());
        m.put("windowSeconds", r.windowSeconds());
        m.put("instanceId", r.instanceId());
        m.put("scope", r.instanceId() == null || r.instanceId().isBlank() ? "global" : "instance");
        m.put("enabled", r.enabled());
        m.put("severity", r.severity());
        m.put("lastFiredAt", r.lastFiredAt() != null ? r.lastFiredAt().toString() : null);
        m.put("lastValue", r.lastValue());
        m.put("lastFiring", r.lastFiring());
        m.put("createdAt", r.createdAt() != null ? r.createdAt().toString() : null);
        m.put("updatedAt", r.updatedAt() != null ? r.updatedAt().toString() : null);
        return m;
    }

    private static Map<String, Object> toActiveView(AlertThresholdRecord rule,
                                                    ResolvedMetric resolved,
                                                    Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thresholdId", rule.id());
        m.put("name", rule.name());
        m.put("metricKey", rule.metricKey());
        m.put("comparator", rule.comparator());
        m.put("thresholdValue", rule.thresholdValue());
        m.put("windowSeconds", rule.windowSeconds());
        m.put("instanceId", rule.instanceId());
        m.put("scope", resolved.scope());
        m.put("severity", rule.severity());
        m.put("value", resolved.value());
        m.put("valueMode", resolved.mode());
        m.put("firedAt", now.toString());
        m.put("message", rule.name() + ": " + rule.metricKey() + "=" + formatNum(resolved.value())
                + " " + rule.comparator() + " " + formatNum(rule.thresholdValue()));
        return m;
    }

    private static String formatNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static int severityRank(String sev) {
        return switch (sev) {
            case "CRITICAL" -> 3;
            case "WARN" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static double asDouble(Object o) {
        if (o == null) return 0d;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static long asLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private record ResolvedMetric(double value, String mode, String scope) {
    }

    /** Request body for create/update. */
    public record ThresholdInput(
            String name,
            String metricKey,
            String comparator,
            Double thresholdValue,
            Integer windowSeconds,
            String instanceId,
            Boolean enabled,
            String severity
    ) {
    }
}
