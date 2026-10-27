package com.whosly.gateway.console.observe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whosly.gateway.console.persist.AlertChannelStore;
import com.whosly.gateway.console.persist.AlertChannelStore.AlertChannelRecord;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.console.security.ConsoleSecretCipher;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alert notification channels (HTTP webhook + SMTP email).
 * Fires on threshold rising-edge (and optional cooldown re-notify while still firing).
 * Not Prometheus / PagerDuty — lab control-plane delivery only.
 */
@Service
public class AlertNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationService.class);

    public static final String TYPE_WEBHOOK = "WEBHOOK";
    public static final String TYPE_EMAIL = "EMAIL";
    private static final Set<String> TYPES = Set.of(TYPE_WEBHOOK, TYPE_EMAIL);
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "CRITICAL");
    private static final String REDACTED = "***";

    private final AlertChannelStore store;
    private final ConsoleSecretCipher cipher;
    private final ObjectMapper mapper;
    private final ConsoleAuditService auditService;
    private final AlertWebhookSender webhookSender;
    private final AlertEmailSender emailSender;

    /** thresholdId|channelId → last notified Instant (in-memory cooldown aid). */
    private final ConcurrentHashMap<String, Instant> lastNotified = new ConcurrentHashMap<>();

    public AlertNotificationService(AlertChannelStore store,
                                    ConsoleSecretCipher cipher,
                                    ObjectMapper mapper,
                                    ObjectProvider<ConsoleAuditService> auditProvider) {
        this(store, cipher, mapper, auditProvider,
                new AlertWebhookSender(mapper), new AlertEmailSender());
    }

    AlertNotificationService(AlertChannelStore store,
                             ConsoleSecretCipher cipher,
                             ObjectMapper mapper,
                             ObjectProvider<ConsoleAuditService> auditProvider,
                             AlertWebhookSender webhookSender,
                             AlertEmailSender emailSender) {
        this.store = Objects.requireNonNull(store, "store");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.auditService = auditProvider != null ? auditProvider.getIfAvailable() : null;
        this.webhookSender = Objects.requireNonNull(webhookSender, "webhookSender");
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender");
    }

    public Map<String, Object> listChannels() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlertChannelRecord r : store.findAll()) {
            items.add(toView(r));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        body.put("types", List.of(TYPE_WEBHOOK, TYPE_EMAIL));
        body.put("severities", List.of("INFO", "WARN", "CRITICAL"));
        body.put("note", "阈值上升沿触发外发；WEBHOOK=HTTP JSON；EMAIL=SMTP(Jakarta Mail)；"
                + "非 Prometheus / 非 PagerDuty；SMTP 密码/Webhook Authorization 落库时可 enc:v1: 密封");
        return body;
    }

    public Map<String, Object> getChannel(String id) {
        return toView(require(id));
    }

    public Map<String, Object> create(ChannelInput input) {
        AlertChannelRecord validated = validateNew(input, null);
        AlertChannelRecord saved = store.insert(validated);
        if (auditService != null) {
            auditService.record("alert-channel.create", null,
                    ConsoleAuditService.detail("id", saved.id(), "type", saved.channelType()));
        }
        return toView(store.findById(saved.id()).orElse(saved));
    }

    public Map<String, Object> update(String id, ChannelInput input) {
        AlertChannelRecord existing = require(id);
        AlertChannelRecord validated = validateNew(input, existing);
        AlertChannelRecord saved = store.update(validated);
        if (auditService != null) {
            auditService.record("alert-channel.update", null,
                    ConsoleAuditService.detail("id", saved.id(), "type", saved.channelType()));
        }
        return toView(store.findById(saved.id()).orElse(saved));
    }

    public Map<String, Object> delete(String id) {
        if (!store.delete(id)) {
            throw new IllegalArgumentException("告警通道不存在: " + id);
        }
        lastNotified.keySet().removeIf(k -> k.endsWith("|" + id) || k.contains("|" + id));
        if (auditService != null) {
            auditService.record("alert-channel.delete", null,
                    ConsoleAuditService.detail("id", id));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        body.put("message", "已删除告警通道");
        return body;
    }

    /**
     * Self-check: deliver a synthetic alert through one channel.
     */
    public Map<String, Object> testChannel(String id) {
        AlertChannelRecord ch = require(id);
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("thresholdId", "test");
        alert.put("name", "通道自检");
        alert.put("metricKey", "deny_count");
        alert.put("comparator", "GTE");
        alert.put("thresholdValue", 1);
        alert.put("value", 1);
        alert.put("valueMode", "absolute");
        alert.put("severity", "INFO");
        alert.put("scope", "global");
        alert.put("instanceId", null);
        alert.put("firedAt", Instant.now().toString());
        alert.put("message", "alert channel self-check from console");
        AlertDeliveryResult result = deliver(ch, alert, "alert.test");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        body.put("channelId", id);
        body.put("type", ch.channelType());
        body.put("message", result.message());
        body.put("statusCode", result.statusCode());
        body.put("sentAt", Instant.now().toString());
        return body;
    }

    /**
     * Called by {@link AlertEvaluationService} when a threshold is firing.
     * {@code risingEdge=true} for newly entered firing; otherwise re-notify only after cooldown.
     */
    public void notifyIfNeeded(Map<String, Object> alert, boolean risingEdge) {
        dispatchFired(alert, risingEdge);
    }

    /** Rising-edge always; sustained firing only after per-channel cooldown. */
    void dispatchFired(Map<String, Object> alert, boolean risingEdge) {
        if (alert == null) {
            return;
        }
        String severity = String.valueOf(alert.getOrDefault("severity", "WARN"));
        String thresholdId = String.valueOf(alert.getOrDefault("thresholdId", ""));
        Instant now = Instant.now();
        for (AlertChannelRecord ch : store.findEnabled()) {
            if (!severityMeetsMin(severity, ch.minSeverity())) {
                continue;
            }
            String key = thresholdId + "|" + ch.id();
            Instant last = lastNotified.get(key);
            boolean pastCooldown = last == null
                    || ch.cooldownSeconds() <= 0
                    || !last.plusSeconds(Math.max(0, ch.cooldownSeconds())).isAfter(now);
            if (risingEdge) {
                // always attempt on rising edge (even if cooldown not elapsed — new incident)
            } else if (!pastCooldown || last == null) {
                // sustained: require a prior successful/attempted notify and elapsed cooldown
                continue;
            }
            AlertDeliveryResult result = deliver(ch, alert, "alert.fired");
            // stamp cooldown on attempt to avoid stampede on failures
            lastNotified.put(key, now);
            if (!result.ok()) {
                log.warn("Alert channel {} delivery failed: {}", ch.id(), result.message());
            }
        }
    }

    private AlertDeliveryResult deliver(AlertChannelRecord ch, Map<String, Object> alert, String event) {
        Instant now = Instant.now();
        try {
            Map<String, Object> config = openConfig(ch.configJson());
            AlertDeliveryResult result;
            if (TYPE_WEBHOOK.equals(ch.channelType())) {
                Map<String, Object> payload = AlertWebhookSender.buildPayload(alert, event);
                result = webhookSender.send(config, payload);
            } else if (TYPE_EMAIL.equals(ch.channelType())) {
                result = emailSender.send(config, alert, event);
            } else {
                result = AlertDeliveryResult.failure("未知通道类型: " + ch.channelType(), null);
            }
            store.updateDeliveryState(ch.id(), result.ok(), result.ok() ? null : result.message(),
                    result.statusCode(), now);
            return result;
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            store.updateDeliveryState(ch.id(), false, msg, null, now);
            return AlertDeliveryResult.failure(msg, null);
        }
    }

    private AlertChannelRecord validateNew(ChannelInput input, AlertChannelRecord existing) {
        if (input == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String type = input.type() == null ? "" : input.type().trim().toUpperCase(Locale.ROOT);
        if (existing != null && (type == null || type.isBlank())) {
            type = existing.channelType();
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("type 须为 WEBHOOK 或 EMAIL");
        }
        String name = input.name() == null || input.name().isBlank()
                ? type.toLowerCase(Locale.ROOT) + "-channel"
                : input.name().trim();
        if (name.length() > 256) {
            throw new IllegalArgumentException("name 过长");
        }
        String sev = input.minSeverity() == null || input.minSeverity().isBlank()
                ? (existing != null ? existing.minSeverity() : "INFO")
                : input.minSeverity().trim().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(sev)) {
            throw new IllegalArgumentException("minSeverity 须为 INFO/WARN/CRITICAL");
        }
        int cooldown = input.cooldownSeconds() != null
                ? input.cooldownSeconds()
                : (existing != null ? existing.cooldownSeconds() : 300);
        if (cooldown < 0) {
            throw new IllegalArgumentException("cooldownSeconds 不能为负");
        }
        if (cooldown > 86400) {
            throw new IllegalArgumentException("cooldownSeconds 过大");
        }
        boolean enabled = input.enabled() == null
                ? (existing == null || existing.enabled())
                : input.enabled();
        Map<String, Object> rawConfig = input.config() != null
                ? new LinkedHashMap<>(input.config())
                : new LinkedHashMap<>();
        if (existing != null && rawConfig.isEmpty()) {
            // keep existing sealed config
            Instant now = Instant.now();
            return new AlertChannelRecord(
                    existing.id(), name, type, enabled, sev, cooldown, existing.configJson(),
                    existing.lastSentAt(), existing.lastOk(), existing.lastError(),
                    existing.lastStatusCode(), existing.createdAt(), now);
        }
        Map<String, Object> prevOpen = existing != null ? openConfig(existing.configJson()) : Map.of();
        Map<String, Object> sealed = sealConfig(type, rawConfig, prevOpen);
        validateConfigShape(type, sealed);
        Instant now = Instant.now();
        return new AlertChannelRecord(
                existing != null ? existing.id() : null,
                name,
                type,
                enabled,
                sev,
                cooldown,
                writeJson(sealed),
                existing != null ? existing.lastSentAt() : null,
                existing != null ? existing.lastOk() : null,
                existing != null ? existing.lastError() : null,
                existing != null ? existing.lastStatusCode() : null,
                existing != null ? existing.createdAt() : now,
                now);
    }

    private void validateConfigShape(String type, Map<String, Object> sealed) {
        Map<String, Object> opened = openSecretsInPlace(new LinkedHashMap<>(sealed));
        if (TYPE_WEBHOOK.equals(type)) {
            String url = str(opened.get("url"));
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("WEBHOOK 需要 config.url");
            }
        } else if (TYPE_EMAIL.equals(type)) {
            if (str(opened.get("smtpHost")) == null || str(opened.get("smtpHost")).isBlank()) {
                throw new IllegalArgumentException("EMAIL 需要 config.smtpHost");
            }
            if (str(opened.get("from")) == null || str(opened.get("from")).isBlank()) {
                throw new IllegalArgumentException("EMAIL 需要 config.from");
            }
            Object to = opened.get("to");
            boolean hasTo = false;
            if (to instanceof List<?> list) {
                hasTo = list.stream().anyMatch(o -> o != null && !o.toString().isBlank());
            } else if (to != null && !to.toString().isBlank()) {
                hasTo = true;
            }
            if (!hasTo) {
                throw new IllegalArgumentException("EMAIL 需要 config.to");
            }
        }
    }

    private Map<String, Object> sealConfig(String type, Map<String, Object> raw, Map<String, Object> prevOpen) {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        if (TYPE_WEBHOOK.equals(type)) {
            Object auth = out.get("authHeader");
            if (auth == null || REDACTED.equals(String.valueOf(auth)) || String.valueOf(auth).isBlank()) {
                if (prevOpen.containsKey("authHeader") && prevOpen.get("authHeader") != null
                        && !String.valueOf(prevOpen.get("authHeader")).isBlank()) {
                    out.put("authHeader", cipher.sealForStorage(String.valueOf(prevOpen.get("authHeader"))));
                } else {
                    out.remove("authHeader");
                }
            } else {
                out.put("authHeader", cipher.sealForStorage(String.valueOf(auth)));
            }
        } else if (TYPE_EMAIL.equals(type)) {
            Object pass = out.get("password");
            if (pass == null || REDACTED.equals(String.valueOf(pass)) || String.valueOf(pass).isBlank()) {
                if (prevOpen.containsKey("password") && prevOpen.get("password") != null
                        && !String.valueOf(prevOpen.get("password")).isBlank()) {
                    out.put("password", cipher.sealForStorage(String.valueOf(prevOpen.get("password"))));
                } else {
                    out.remove("password");
                }
            } else {
                out.put("password", cipher.sealForStorage(String.valueOf(pass)));
            }
            // normalize to list
            Object to = out.get("to");
            if (to instanceof String s) {
                List<String> list = new ArrayList<>();
                for (String part : s.split("[,;\\s]+")) {
                    if (!part.isBlank()) {
                        list.add(part.trim());
                    }
                }
                out.put("to", list);
            }
        }
        return out;
    }

    private Map<String, Object> openConfig(String json) {
        try {
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            return openSecretsInPlace(raw);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置 JSON 损坏: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> openSecretsInPlace(Map<String, Object> raw) {
        if (raw.containsKey("authHeader") && raw.get("authHeader") != null) {
            raw.put("authHeader", cipher.openFromStorage(String.valueOf(raw.get("authHeader"))));
        }
        if (raw.containsKey("password") && raw.get("password") != null) {
            raw.put("password", cipher.openFromStorage(String.valueOf(raw.get("password"))));
        }
        return raw;
    }

    private Map<String, Object> toView(AlertChannelRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("type", r.channelType());
        m.put("enabled", r.enabled());
        m.put("minSeverity", r.minSeverity());
        m.put("cooldownSeconds", r.cooldownSeconds());
        Map<String, Object> cfg;
        try {
            cfg = mapper.readValue(r.configJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            cfg = new LinkedHashMap<>();
            cfg.put("_error", "config parse failed");
        }
        boolean authConfigured = cfg.containsKey("authHeader") && cfg.get("authHeader") != null
                && !String.valueOf(cfg.get("authHeader")).isBlank();
        boolean passwordConfigured = cfg.containsKey("password") && cfg.get("password") != null
                && !String.valueOf(cfg.get("password")).isBlank();
        cfg.remove("authHeader");
        cfg.remove("password");
        if (authConfigured) {
            cfg.put("authHeaderConfigured", true);
        }
        if (passwordConfigured) {
            cfg.put("passwordConfigured", true);
        }
        m.put("config", cfg);
        m.put("lastSentAt", r.lastSentAt() != null ? r.lastSentAt().toString() : null);
        m.put("lastOk", r.lastOk());
        m.put("lastError", r.lastError());
        m.put("lastStatusCode", r.lastStatusCode());
        m.put("createdAt", r.createdAt() != null ? r.createdAt().toString() : null);
        m.put("updatedAt", r.updatedAt() != null ? r.updatedAt().toString() : null);
        return m;
    }

    private AlertChannelRecord require(String id) {
        return store.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警通道不存在: " + id));
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化 config: " + e.getMessage(), e);
        }
    }

    static boolean severityMeetsMin(String actual, String min) {
        return severityRank(actual) >= severityRank(min);
    }

    private static int severityRank(String sev) {
        if (sev == null) {
            return 0;
        }
        return switch (sev.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 3;
            case "WARN" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    public record ChannelInput(
            String name,
            String type,
            Boolean enabled,
            String minSeverity,
            Integer cooldownSeconds,
            Map<String, Object> config
    ) {
    }
}
