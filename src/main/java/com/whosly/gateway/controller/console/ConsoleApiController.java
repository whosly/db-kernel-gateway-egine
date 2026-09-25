package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;
import org.springframework.stereotype.Component;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import org.springframework.web.server.ResponseStatusException;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.console.observe.MetricsHistorySampler;
import com.whosly.gateway.runtime.observe.RecentTrafficRing;
import com.whosly.gateway.console.observe.TrafficAuditBrowseService;
import com.whosly.gateway.console.InstanceBackendHealthService;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import com.whosly.gateway.adapter.protocol.SessionDirtiness;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.GatewayInstance;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.persist.ConsoleAuditStore.ConsoleAuditRecord;
import com.whosly.gateway.console.persist.MaskingRuleRecord;
import com.whosly.gateway.console.schema.InstanceSchemaColumnsService;
import com.whosly.gateway.console.schema.InstanceSchemaColumnsService.SchemaConnectException;
import com.whosly.gateway.console.persist.ConsoleSqlHistoryStore;
import com.whosly.gateway.console.persist.ConsoleSqlHistoryStore.SqlHistoryRecord;
import com.whosly.gateway.console.persist.ConsoleSqlSnippetStore;
import com.whosly.gateway.console.persist.ConsoleSqlSnippetStore.SqlSnippetRecord;
import com.whosly.gateway.console.security.ConsoleAuditService;
import com.whosly.gateway.console.security.ConsoleMaskingKeyService;
import com.whosly.gateway.console.security.ConsoleSecretCipher;
import com.whosly.gateway.console.security.RiskPolicyService;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CreateInstanceRequest;
import com.whosly.gateway.runtime.GatewayListenerRuntime.UpdateInstanceRequest;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CloneInstanceRequest;
import com.whosly.gateway.console.sql.InstanceSqlExecuteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Protocol-agnostic console REST API ({@code /console/api/*}).
 *
 * <p>First-class resource is the <em>gateway instance</em>; database type is only
 * an attribute. Existing {@code /gateway/*} endpoints remain unchanged.</p>
 */
@Component
public class ConsoleApiController {

    private final SupportedDatabaseCatalog catalog;
    private final GatewayInstanceRegistry instanceRegistry;
    private final ProtocolAdapter protocolAdapter;
    private final GatewayRuntimeMetrics runtimeMetrics;
    private final GatewayConfig gatewayConfig;
    private final ConsoleAuditService auditService;
    private final ConsoleMaskingKeyService maskingKeyService;
    private final ConsoleSecretCipher secretCipher;
    private final InstanceSchemaColumnsService schemaColumnsService;
    private final InstanceBackendHealthService healthService;
    private final RecentTrafficRing recentTrafficRing;
    private final GatewayListenerRuntime listenerRuntime;
    private final RiskPolicyService riskPolicyService;
    private final MetricsHistorySampler metricsHistorySampler;
    private final InstanceSqlExecuteService sqlExecuteService;
    private final ConsoleSqlHistoryStore sqlHistoryStore;
    private final ConsoleSqlSnippetStore sqlSnippetStore;
    private final TrafficAuditBrowseService trafficAuditBrowseService;

    /** Test-friendly constructor (security extras optional). */
    public ConsoleApiController(SupportedDatabaseCatalog catalog,
                                GatewayInstanceRegistry instanceRegistry,
                                ProtocolAdapter protocolAdapter,
                                GatewayRuntimeMetrics runtimeMetrics,
                                GatewayConfig gatewayConfig) {
        this(catalog, instanceRegistry, protocolAdapter, runtimeMetrics, gatewayConfig,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Autowired
    public ConsoleApiController(SupportedDatabaseCatalog catalog,
                                GatewayInstanceRegistry instanceRegistry,
                                ProtocolAdapter protocolAdapter,
                                GatewayRuntimeMetrics runtimeMetrics,
                                GatewayConfig gatewayConfig,
                                @Autowired(required = false) ConsoleAuditService auditService,
                                @Autowired(required = false) ConsoleMaskingKeyService maskingKeyService,
                                @Autowired(required = false) InstanceSchemaColumnsService schemaColumnsService,
                                @Autowired(required = false) InstanceBackendHealthService healthService,
                                @Autowired(required = false) RecentTrafficRing recentTrafficRing,
                                @Autowired(required = false) GatewayListenerRuntime listenerRuntime,
                                @Autowired(required = false) RiskPolicyService riskPolicyService,
                                @Autowired(required = false) MetricsHistorySampler metricsHistorySampler,
                                @Autowired(required = false) InstanceSqlExecuteService sqlExecuteService,
                                @Autowired(required = false) ConsoleSqlHistoryStore sqlHistoryStore,
                                @Autowired(required = false) ConsoleSqlSnippetStore sqlSnippetStore,
                                @Autowired(required = false) TrafficAuditBrowseService trafficAuditBrowseService,
                                @Autowired(required = false) ConsoleSecretCipher secretCipher) {
        this.catalog = catalog;
        this.instanceRegistry = instanceRegistry;
        this.protocolAdapter = protocolAdapter;
        this.runtimeMetrics = runtimeMetrics != null ? runtimeMetrics : GatewayRuntimeMetrics.noop();
        this.gatewayConfig = gatewayConfig;
        this.auditService = auditService;
        this.maskingKeyService = maskingKeyService;
        this.schemaColumnsService = schemaColumnsService;
        this.healthService = healthService;
        this.recentTrafficRing = recentTrafficRing;
        this.listenerRuntime = listenerRuntime;
        this.riskPolicyService = riskPolicyService;
        this.metricsHistorySampler = metricsHistorySampler;
        this.sqlExecuteService = sqlExecuteService;
        this.sqlHistoryStore = sqlHistoryStore;
        this.sqlSnippetStore = sqlSnippetStore;
        this.trafficAuditBrowseService = trafficAuditBrowseService;
        this.secretCipher = secretCipher;
    }

    public Map<String, Object> supportedDatabases() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("databases", catalog.listAll());
        body.put("note", "类型目录（可插拔适配器注册表），与实例注册表分离");
        return body;
    }

    public Map<String, Object> listInstances(
            String status,
            String dbType,
            String q) {
        List<GatewayInstance> instances = instanceRegistry.listInstances();
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase();
            instances = instances.stream()
                    .filter(i -> i.status().name().equalsIgnoreCase(s))
                    .collect(Collectors.toList());
        }
        if (dbType != null && !dbType.isBlank()) {
            String t = dbType.trim().toLowerCase();
            instances = instances.stream()
                    .filter(i -> i.dbType() != null && i.dbType().equalsIgnoreCase(t))
                    .collect(Collectors.toList());
        }
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase();
            instances = instances.stream().filter(i ->
                    containsIgnore(i.id(), needle)
                            || containsIgnore(i.name(), needle)
                            || containsIgnore(i.listenHost(), needle)
                            || containsIgnore(i.targetHost(), needle)
            ).collect(Collectors.toList());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instances", instances);
        body.put("count", instances.size());
        body.put("byStatus", instances.stream()
                .collect(Collectors.groupingBy(i -> i.status().name(), Collectors.counting())));
        if (status != null && !status.isBlank()) body.put("statusFilter", status.trim());
        if (dbType != null && !dbType.isBlank()) body.put("dbTypeFilter", dbType.trim());
        if (q != null && !q.isBlank()) body.put("q", q.trim());
        return body;
    }

    private static boolean containsIgnore(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    public GatewayInstance getInstance(String id) {
        return instanceRegistry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + id));
    }

    public Map<String, Object> instanceStatus(String id) {
        return instanceRegistry.statusOf(id);
    }

    public Map<String, Object> instanceMetrics(String id) {
        return instanceRegistry.metricsOf(id);
    }

    public Map<String, Object> startInstance(String id) {
        Map<String, Object> result = instanceRegistry.start(id);
        audit("instance.start", id, ConsoleAuditService.detail("ok", result.get("ok")));
        return result;
    }

    public Map<String, Object> stopInstance(String id) {
        Map<String, Object> result = instanceRegistry.stop(id);
        audit("instance.stop", id, ConsoleAuditService.detail("ok", result.get("ok")));
        return result;
    }

    public GatewayInstance createInstance(CreateInstanceBody body) {
        if (body == null || body.dbType() == null || body.dbType().isBlank()) {
            throw new IllegalArgumentException("dbType is required");
        }
        if (body.listenPort() == null) {
            throw new IllegalArgumentException("listenPort is required");
        }
        if (body.targetHost() == null || body.targetHost().isBlank()) {
            throw new IllegalArgumentException("targetHost is required");
        }
        if (body.targetPort() == null) {
            throw new IllegalArgumentException("targetPort is required");
        }
        CreateInstanceRequest request = new CreateInstanceRequest(
                body.id(),
                body.name(),
                body.dbType(),
                body.listenHost(),
                body.listenPort(),
                body.targetHost(),
                body.targetPort(),
                body.targetDatabase(),
                body.targetUsername(),
                body.targetPassword(),
                body.enabled());
        GatewayInstance created = instanceRegistry.create(request);
        audit("instance.create", created.id(), ConsoleAuditService.detail(
                "dbType", created.dbType(),
                "listenPort", created.listenPort(),
                "passwordConfigured", created.passwordConfigured()));
        return created;
    }

    public Map<String, Object> deleteInstance(String id) {
        Map<String, Object> result = instanceRegistry.remove(id);
        if (Boolean.FALSE.equals(result.get("ok"))) {
            throw new IllegalArgumentException(String.valueOf(result.get("message")));
        }
        audit("instance.delete", id, ConsoleAuditService.detail("ok", true));
        return result;
    }

    public GatewayInstance updateInstance(String id,
                                          UpdateInstanceBody body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        UpdateInstanceRequest request = new UpdateInstanceRequest(
                body.name(),
                body.listenHost(),
                body.listenPort(),
                body.targetHost(),
                body.targetPort(),
                body.targetDatabase(),
                body.targetUsername(),
                body.targetPassword(),
                body.enabled());
        GatewayInstance updated = instanceRegistry.update(id, request);
        audit("instance.update", updated.id(), ConsoleAuditService.detail(
                "listenPort", updated.listenPort(),
                "passwordConfigured", updated.passwordConfigured(),
                "enabled", updated.enabled()));
        return updated;
    }

    public GatewayInstance cloneInstance(String id,
                                         CloneInstanceBody body) {
        CloneInstanceRequest request = body == null
                ? new CloneInstanceRequest(null, null, null, true)
                : new CloneInstanceRequest(body.id(), body.name(), body.listenPort(),
                body.copyMaskingRules() == null || body.copyMaskingRules());
        GatewayInstance cloned = instanceRegistry.cloneInstance(id, request);
        audit("instance.clone", cloned.id(), ConsoleAuditService.detail(
                "from", id,
                "listenPort", cloned.listenPort(),
                "passwordConfigured", cloned.passwordConfigured()));
        return cloned;
    }

    public Map<String, Object> importInstances(ImportInstancesBody body) {
        if (body == null || body.instances() == null) {
            throw new IllegalArgumentException("instances array is required");
        }
        boolean replace = Boolean.TRUE.equals(body.replace());
        boolean skipExisting = body.skipExisting() == null || body.skipExisting();
        if (replace) {
            skipExisting = false;
        }
        Map<String, Object> result = instanceRegistry.importInstances(body.instances(), replace, skipExisting);
        audit("instance.import", null, ConsoleAuditService.detail(
                "created", result.get("created"),
                "skipped", result.get("skipped"),
                "failed", result.get("failed")));
        return result;
    }

    public Map<String, Object> bulkInstances(BulkInstancesBody body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Map<String, Object> result = instanceRegistry.bulk(body.action(), body.ids());
        audit("instance.bulk", null, ConsoleAuditService.detail(
                "action", body.action(),
                "okCount", result.get("okCount"),
                "failCount", result.get("failCount")));
        return result;
    }

    public Map<String, Object> executeSql(String id,
                                          SqlExecuteBody body) {
        if (sqlExecuteService == null) {
            throw new IllegalStateException("SQL execute service is not available");
        }
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        boolean continueOnError = Boolean.TRUE.equals(body.continueOnError());
        Map<String, Object> result;
        try {
            result = sqlExecuteService.execute(
                    id, body.sql(), body.maxRows(), body.timeoutMs(),
                    body.executionId(), continueOnError);
            boolean ok = !Boolean.FALSE.equals(result.get("ok"));
            recordSqlHistory(id, body.sql(), ok, result);
        } catch (RuntimeException ex) {
            recordSqlHistoryFailure(id, body.sql(), ex);
            throw ex;
        }
        String truncatedSql = InstanceSqlExecuteService.truncateForAudit(body.sql(), 200);
        if (gatewayConfig.isAuditMaskStatements() && truncatedSql.length() > 80) {
            truncatedSql = truncatedSql.substring(0, 80) + "…";
        }
        audit("sql.execute", id, ConsoleAuditService.detail(
                "ok", result.get("ok"),
                "rowCount", result.get("rowCount"),
                "statementCount", result.get("statementCount"),
                "executionId", result.get("executionId"),
                "durationMs", result.get("durationMs"),
                "sql", truncatedSql));
        return result;
    }

    /**
     * Best-effort cancel of an in-flight console SQL execute for this instance.
     * Body: {@code { "executionId": "..." }}.
     */
    public Map<String, Object> cancelSql(String id,
                                         SqlCancelBody body) {
        if (sqlExecuteService == null) {
            throw new IllegalStateException("SQL execute service is not available");
        }
        String executionId = body != null ? body.executionId() : null;
        Map<String, Object> result = sqlExecuteService.cancel(id, executionId);
        audit("sql.cancel", id, ConsoleAuditService.detail(
                "executionId", executionId,
                "found", result.get("found"),
                "ok", result.get("ok")));
        return result;
    }

    /**
     * Best-effort cancel by executionId (protocol-agnostic).
     */
    public Map<String, Object> cancelSqlExecution(String executionId) {
        if (sqlExecuteService == null) {
            throw new IllegalStateException("SQL execute service is not available");
        }
        Map<String, Object> result = sqlExecuteService.cancel(executionId);
        Object instanceId = result.get("instanceId");
        audit("sql.cancel", instanceId != null ? instanceId.toString() : null, ConsoleAuditService.detail(
                "executionId", executionId,
                "found", result.get("found"),
                "ok", result.get("ok")));
        return result;
    }

    public Map<String, Object> health() {
        List<GatewayInstance> instances = instanceRegistry.listInstances();
        long running = instances.stream().filter(i -> i.status() == GatewayInstance.InstanceStatus.RUNNING).count();
        long bound = instances.stream().filter(GatewayInstance::bound).count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", running > 0 ? "UP" : (bound > 0 ? "DOWN" : "IDLE"));
        body.put("instanceCount", instances.size());
        body.put("runningCount", running);
        body.put("boundCount", bound);
        body.put("processAdapterRunning", protocolAdapter.isRunning());
        body.put("processProtocol", protocolAdapter.getProtocolName());
        body.put("processProxyPort", protocolAdapter.getDefaultPort());
        return body;
    }

    public Map<String, Object> configSummary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("process", Map.of(
                "proxyDbType", gatewayConfig.getProxyDbType(),
                "proxyPort", gatewayConfig.getProxyPort()
        ));

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("host", gatewayConfig.getTargetHost());
        target.put("port", gatewayConfig.getTargetPort());
        target.put("username", blankToNull(gatewayConfig.getTargetUsername()));
        target.put("database", blankToNull(gatewayConfig.getTargetDatabase()));
        target.put("passwordConfigured", hasText(gatewayConfig.getTargetPassword()));
        target.put("password", "********");
        body.put("target", target);

        body.put("instances", instanceRegistry.listInstances().stream().map(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.id());
            row.put("name", i.name());
            row.put("dbType", i.dbType());
            row.put("listenPort", i.listenPort());
            row.put("status", i.status().name());
            row.put("bound", i.bound());
            row.put("source", i.source());
            row.put("passwordConfigured", i.passwordConfigured());
            return row;
        }).toList());

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("running", protocolAdapter.isRunning());
        runtime.put("protocol", protocolAdapter.getProtocolName());
        runtime.put("listenPort", protocolAdapter.getDefaultPort());
        runtime.put("activeSessions", protocolAdapter.getActiveSessions().size());
        if (protocolAdapter instanceof AbstractProtocolAdapter abstractAdapter) {
            runtime.put("activeConnections", abstractAdapter.getActiveConnectionCount());
            runtime.put("maxConnections", abstractAdapter.getMaxConnections());
        }
        body.put("runtime", runtime);
        body.put("metrics", runtimeMetrics.snapshot());
        Map<String, Object> console = new LinkedHashMap<>();
        console.put("secretKeyConfigured", gatewayConfig.isConsoleSecretKeyConfigured());
        console.put("requireSecretEncryption", gatewayConfig.isConsoleRequireSecretEncryption());
        if (secretCipher != null) {
            console.putAll(secretCipher.status());
        } else {
            console.put("masterKeyConfigured", gatewayConfig.isConsoleSecretKeyConfigured());
            console.put("allowsPlaintextWrites",
                    !gatewayConfig.isConsoleRequireSecretEncryption()
                            && !gatewayConfig.isConsoleSecretKeyConfigured());
        }
        body.put("console", console);
        return body;
    }

    public Map<String, Object> overview() {
        List<GatewayInstance> instances = instanceRegistry.listInstances();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("health", health());
        body.put("instances", instances);
        body.put("databases", catalog.listAll());
        body.put("metrics", aggregateInstanceMetrics(instances));
        body.put("metricsScope", "all-instances");
        body.put("legacyMetrics", runtimeMetrics.snapshot());
        body.put("config", configSummary());
        body.put("byStatus", instances.stream()
                .collect(Collectors.groupingBy(i -> i.status().name(), Collectors.counting())));
        return body;
    }

    static Map<String, Long> aggregateInstanceMetrics(List<GatewayInstance> instances) {
        Map<String, Long> summed = new LinkedHashMap<>();
        for (GatewayInstance instance : instances) {
            Map<String, Long> metrics = instance.metrics();
            if (metrics == null || metrics.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Long> entry : metrics.entrySet()) {
                summed.merge(entry.getKey(), entry.getValue() != null ? entry.getValue() : 0L, Long::sum);
            }
        }
        return Map.copyOf(summed);
    }

    // ---- Instance masking rules (Phase A+) ----

    public Map<String, Object> listMaskingRules(String id) {
        List<MaskingRuleRecord> rules = instanceRegistry.listMaskingRules(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", id);
        body.put("rules", rules.stream().map(ConsoleApiController::toMaskingRuleDto).toList());
        body.put("count", rules.size());
        return body;
    }

    public Map<String, Object> createMaskingRule(String id,
                                                 MaskingRuleBody body) {
        MaskingRuleRecord saved = instanceRegistry.createMaskingRule(id, fromBody(id, body, null));
        audit("masking-rule.create", id, ConsoleAuditService.detail(
                "ruleId", saved.id(), "strategy", saved.strategy(), "name", saved.name()));
        return toMaskingRuleDto(saved);
    }

    public Map<String, Object> updateMaskingRule(String id,
                                                 String ruleId,
                                                 MaskingRuleBody body) {
        MaskingRuleRecord saved = instanceRegistry.updateMaskingRule(id, ruleId, fromBody(id, body, ruleId));
        audit("masking-rule.update", id, ConsoleAuditService.detail(
                "ruleId", saved.id(), "strategy", saved.strategy()));
        return toMaskingRuleDto(saved);
    }

    public Map<String, Object> replaceMaskingRules(String id,
                                                   List<MaskingRuleBody> bodies) {
        if (bodies == null) {
            throw new IllegalArgumentException("request body must be a JSON array of rules");
        }
        List<MaskingRuleRecord> drafts = new ArrayList<>();
        for (MaskingRuleBody body : bodies) {
            drafts.add(fromBody(id, body, body != null ? body.id() : null));
        }
        List<MaskingRuleRecord> saved = instanceRegistry.replaceMaskingRules(id, drafts);
        audit("masking-rule.replace", id, ConsoleAuditService.detail("count", saved.size()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("instanceId", id);
        resp.put("rules", saved.stream().map(ConsoleApiController::toMaskingRuleDto).toList());
        resp.put("count", saved.size());
        return resp;
    }

    public Map<String, Object> deleteMaskingRule(String id,
                                                 String ruleId) {
        Map<String, Object> result = instanceRegistry.deleteMaskingRule(id, ruleId);
        audit("masking-rule.delete", id, ConsoleAuditService.detail("ruleId", ruleId));
        return result;
    }

    public Map<String, Object> reloadMasking(String id) {
        return instanceRegistry.reloadMasking(id);
    }


    // ---- Sessions / health / export / recent statements (industry-aligned control plane) ----

    public Map<String, Object> listSessions(String id) {
        GatewayInstance instance = instanceRegistry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + id));
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (SessionSnapshot snap : instanceRegistry.sessionSnapshots(id)) {
            sessions.add(toSessionDto(snap));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", instance.id());
        body.put("sessions", sessions);
        body.put("count", sessions.size());
        return body;
    }

    public Map<String, Object> killSession(String id,
                                           String connectionId) {
        instanceRegistry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + id));
        if (!instanceRegistry.killSession(id, connectionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown session connectionId: " + connectionId);
        }
        audit("session.kill", id, ConsoleAuditService.detail("connectionId", connectionId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("instanceId", id);
        body.put("connectionId", connectionId);
        body.put("message", "已关闭客户端连接（仅客户端腿；不代发协议级 KILL 到后端）");
        return body;
    }

    public Map<String, Object> healthCheck(String id) {
        if (healthService == null) {
            throw new IllegalStateException("Health check service is not available");
        }
        return healthService.check(id);
    }

    public Map<String, Object> healthCheckGet(String id) {
        return healthCheck(id);
    }

    public List<Map<String, Object>> exportInstances() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GatewayInstance i : instanceRegistry.listInstances()) {
            out.add(toExportInstance(i));
        }
        return out;
    }

    public Map<String, Object> exportConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exportedAt", java.time.Instant.now().toString());
        body.put("catalog", catalog.listAll());
        body.put("instances", exportInstances());
        Map<String, Object> security = new LinkedHashMap<>();
        if (maskingKeyService != null) {
            Map<String, Object> keyStatus = maskingKeyService.status();
            security.put("maskingKeyConfigured", Boolean.TRUE.equals(keyStatus.get("configured")));
            security.put("maskingKeySource", keyStatus.get("source"));
        } else {
            security.put("maskingKeyConfigured", false);
        }
        security.put("consoleSecretKeyConfigured", gatewayConfig.isConsoleSecretKeyConfigured());
        body.put("security", security);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("enabled", gatewayConfig.isAuditEnabled());
        audit.put("maskStatements", gatewayConfig.isAuditMaskStatements());
        audit.put("destination", gatewayConfig.getAuditDestination());
        body.put("audit", audit);
        body.put("note", "Non-secret export only; passwords and keys omitted");
        // Sanity: never leak password material
        String asText = body.toString();
        if (asText.contains("password=") || asText.contains("targetPassword")) {
            throw new IllegalStateException("export leaked password field");
        }
        return body;
    }

    public Map<String, Object> recentStatements(String id,
                                                int limit) {
        instanceRegistry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + id));
        int lim = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> entries = new ArrayList<>();
        if (recentTrafficRing != null) {
            for (RecentTrafficRing.RecentEntry e : recentTrafficRing.recent(id, lim)) {
                entries.add(e.toMap());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", id);
        body.put("entries", entries);
        body.put("count", entries.size());
        body.put("capacity", recentTrafficRing != null ? recentTrafficRing.capacity() : 0);
        body.put("note", "内存环，重启丢失；不能替代 audit spool");
        return body;
    }

    // ---- Schema column hints (Phase A+ leftover) ----

    public Map<String, Object> schemaCatalog(String id) {
        if (schemaColumnsService == null) {
            throw new IllegalStateException("Schema columns service is not available");
        }
        return schemaColumnsService.listCatalog(id);
    }

    public Map<String, Object> schemaColumns(String id,
                                             String table,
                                             String schema) {
        if (schemaColumnsService == null) {
            throw new IllegalStateException("Schema columns service is not available");
        }
        return schemaColumnsService.listColumns(id, table, schema);
    }

    // ---- SQL IDE: history + snippets ----

    public Map<String, Object> sqlHistory(
            String instanceId,
            int limit) {
        if (sqlHistoryStore == null) {
            throw new IllegalStateException("SQL history store is not available");
        }
        List<SqlHistoryRecord> rows = sqlHistoryStore.list(instanceId, limit);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SqlHistoryRecord r : rows) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", r.id());
            e.put("instanceId", r.instanceId());
            e.put("sql", r.sqlText());
            e.put("ok", r.ok());
            e.put("durationMs", r.durationMs());
            e.put("rowCount", r.rowCount());
            e.put("createdAt", r.createdAt() != null ? r.createdAt().toString() : null);
            e.put("actor", r.actor());
            entries.add(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", entries);
        body.put("count", entries.size());
        body.put("cap", ConsoleSqlHistoryStore.MAX_ROWS);
        return body;
    }

    public Map<String, Object> clearSqlHistory(String id) {
        if (sqlHistoryStore == null) {
            throw new IllegalStateException("SQL history store is not available");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (hasText(id)) {
            boolean ok = sqlHistoryStore.deleteById(id.trim());
            body.put("ok", ok);
            body.put("deleted", ok ? 1 : 0);
        } else {
            int n = sqlHistoryStore.deleteAll();
            body.put("ok", true);
            body.put("deleted", n);
        }
        return body;
    }

    public Map<String, Object> listSnippets() {
        if (sqlSnippetStore == null) {
            throw new IllegalStateException("SQL snippet store is not available");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SqlSnippetRecord r : sqlSnippetStore.listAll()) {
            entries.add(snippetToMap(r));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snippets", entries);
        body.put("count", entries.size());
        return body;
    }

    public Map<String, Object> createSnippet(SqlSnippetBody body) {
        if (sqlSnippetStore == null) {
            throw new IllegalStateException("SQL snippet store is not available");
        }
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        SqlSnippetRecord saved = sqlSnippetStore.insert(body.name(), body.sql());
        audit("sql.snippet.create", null, ConsoleAuditService.detail("id", saved.id(), "name", saved.name()));
        return snippetToMap(saved);
    }

    public Map<String, Object> updateSnippet(String id, SqlSnippetBody body) {
        if (sqlSnippetStore == null) {
            throw new IllegalStateException("SQL snippet store is not available");
        }
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        SqlSnippetRecord saved = sqlSnippetStore.update(id, body.name(), body.sql());
        audit("sql.snippet.update", null, ConsoleAuditService.detail("id", saved.id(), "name", saved.name()));
        return snippetToMap(saved);
    }

    public Map<String, Object> deleteSnippet(String id) {
        if (sqlSnippetStore == null) {
            throw new IllegalStateException("SQL snippet store is not available");
        }
        boolean ok = sqlSnippetStore.delete(id);
        if (!ok) {
            throw new IllegalArgumentException("Unknown snippet id: " + id);
        }
        audit("sql.snippet.delete", null, ConsoleAuditService.detail("id", id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        return body;
    }

    // ---- Security: masking key + audit ----

    public Map<String, Object> maskingKeyStatus() {
        return requireMaskingKeyService().status();
    }

    public Map<String, Object> putMaskingKey(MaskingKeyBody body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return requireMaskingKeyService().putKey(body.keyId(), body.keyBase64());
    }

    public Map<String, Object> deleteMaskingKey() {
        return requireMaskingKeyService().clearKey();
    }

    /**
     * Control-plane password envelope status (no key material).
     * Used by Ops / instance-create UI when require-secret-encryption is on.
     */
    public Map<String, Object> secretEncryptionStatus() {
        if (secretCipher != null) {
            return secretCipher.status();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("masterKeyConfigured", gatewayConfig.isConsoleSecretKeyConfigured());
        body.put("requireSecretEncryption", gatewayConfig.isConsoleRequireSecretEncryption());
        body.put("allowsPlaintextWrites",
                !gatewayConfig.isConsoleRequireSecretEncryption()
                        && !gatewayConfig.isConsoleSecretKeyConfigured());
        body.put("storageMode", gatewayConfig.isConsoleSecretKeyConfigured()
                ? "encrypted"
                : (gatewayConfig.isConsoleRequireSecretEncryption()
                ? "require-encrypted-blocked" : "lab-plaintext"));
        body.put("help", "见 gateway.console.secret-key-base64 / require-secret-encryption");
        return body;
    }

    public Map<String, Object> listAudit(int limit,
                                          String action) {
        if (auditService == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("entries", List.of());
            empty.put("count", 0);
            return empty;
        }
        List<ConsoleAuditRecord> rows = auditService.list(limit, action);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("at", r.at() != null ? r.at().toString() : null);
            m.put("action", r.action());
            m.put("instanceId", r.instanceId());
            m.put("detailJson", r.detailJson());
            m.put("actor", r.actor());
            return m;
        }).toList());
        body.put("count", rows.size());
        if (action != null && !action.isBlank()) {
            body.put("actionFilter", action.trim());
        }
        return body;
    }

    public Map<String, Object> auditStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", gatewayConfig.isAuditEnabled());
        body.put("destination", gatewayConfig.getAuditDestination());
        body.put("spoolDir", gatewayConfig.getAuditSpoolDir());
        body.put("maskStatements", gatewayConfig.isAuditMaskStatements());
        body.put("shipperRunning", gatewayConfig.isAuditShipperRunning());
        Long bytesHint = gatewayConfig.getAuditSpoolBytesHint();
        body.put("recordsPendingHint", bytesHint); // bytes in active segment; not exact record count
        body.put("consoleAuditCount", auditService != null ? auditService.count() : 0);
        body.put("help", "流量审计见 docs/OPS.md · gateway.audit.*；本接口仅非密钥状态");
        return body;
    }

    /**
     * Traffic / spool audit content browse (not control-plane ops audit).
     *
     * <p>{@code source}=auto|ring|spool|jdbc；分页 {@code limit}+{@code before}（epoch ms，排他上界）。</p>
     */
    public Map<String, Object> listAuditSpool(
            int limit,
            Long before,
            String source,
            String protocol,
            String operation) {
        TrafficAuditBrowseService svc = trafficAuditBrowseService;
        if (svc == null) {
            svc = new TrafficAuditBrowseService(gatewayConfig, recentTrafficRing);
        }
        return svc.browse(limit, before, source, protocol, operation);
    }

    /** Alias for {@link #listAuditSpool}. */
    public Map<String, Object> listAuditRecords(
            int limit,
            Long before,
            String source,
            String protocol,
            String operation) {
        return listAuditSpool(limit, before, source, protocol, operation);
    }

    public Map<String, Object> metricsHistory(
            String instanceId,
            int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (metricsHistorySampler == null) {
            body.put("intervalSeconds", 5);
            body.put("points", List.of());
            body.put("count", 0);
            body.put("note", "采样器未启用");
            return body;
        }
        if (instanceId != null && !instanceId.isBlank()) {
            instanceRegistry.findById(instanceId.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + instanceId));
        }
        List<Map<String, Object>> points = metricsHistorySampler.history(instanceId, limit);
        body.put("intervalSeconds", metricsHistorySampler.intervalSeconds());
        body.put("instanceId", (instanceId == null || instanceId.isBlank()) ? null : instanceId.trim());
        body.put("scope", (instanceId == null || instanceId.isBlank()) ? "overview" : "instance");
        body.put("points", points);
        body.put("count", points.size());
        body.put("capacity", metricsHistorySampler.capacity());
        body.put("note", "内存环，重启丢失；不替代 Prometheus");
        return body;
    }

    public Map<String, Object> getRiskPolicy() {
        if (riskPolicyService == null) {
            throw new IllegalStateException("Risk policy service is not available");
        }
        return riskPolicyService.getView();
    }

    public Map<String, Object> putRiskPolicy(RiskPolicyBody body) {
        if (riskPolicyService == null) {
            throw new IllegalStateException("Risk policy service is not available");
        }
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return riskPolicyService.put(body.enabled(), body.deniedOperations(), body.deniedStatementKeywords());
    }


    static Map<String, Object> toSessionDto(SessionSnapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connectionId", snap.connectionId());
        m.put("protocolName", snap.protocolName());
        m.put("state", snap.state() != null ? snap.state().name() : null);
        m.put("confidence", snap.confidence() != null ? snap.confidence().name() : null);
        m.put("inTransaction", snap.inTransaction());
        m.put("clientUser", snap.clientUser().orElse(null));
        m.put("clientDatabase", snap.clientDatabase().orElse(null));
        m.put("dirtiness", dirtinessSummary(snap.dirtiness()));
        m.put("connectedAt", snap.connectedAt() != null ? snap.connectedAt().toString() : null);
        m.put("lastActivity", snap.lastActivity() != null ? snap.lastActivity().toString() : null);
        return m;
    }

    static Map<String, Object> dirtinessSummary(SessionDirtiness d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d == null) {
            m.put("clean", true);
            return m;
        }
        m.put("clean", d.isClean());
        m.put("hasPreparedStatements", d.hasPreparedStatements());
        m.put("hasSessionSettings", d.hasSessionSettings());
        m.put("hasTemporaryObjects", d.hasTemporaryObjects());
        m.put("hasUserVariables", d.hasUserVariables());
        m.put("hasLocks", d.hasLocks());
        m.put("tooComplexToReset", d.tooComplexToReset());
        return m;
    }

    private Map<String, Object> toExportInstance(GatewayInstance i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("name", i.name());
        m.put("dbType", i.dbType());
        m.put("listenHost", i.listenHost());
        m.put("listenPort", i.listenPort());
        m.put("enabled", i.enabled());
        m.put("status", i.status().name());
        m.put("bound", i.bound());
        m.put("targetHost", i.targetHost());
        m.put("targetPort", i.targetPort());
        m.put("targetDatabase", i.targetDatabase());
        m.put("targetUsername", i.targetUsername());
        m.put("passwordConfigured", i.passwordConfigured());
        m.put("source", i.source());
        try {
            List<MaskingRuleRecord> rules = instanceRegistry.listMaskingRules(i.id());
            m.put("maskingRules", rules.stream().map(r -> {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("id", r.id());
                meta.put("name", r.name());
                meta.put("strategy", r.strategy());
                meta.put("priority", r.priority());
                meta.put("columnName", r.columnName());
                meta.put("tableName", r.tableName());
                meta.put("namePattern", r.namePattern());
                meta.put("enabled", r.enabled());
                return meta;
            }).toList());
        } catch (RuntimeException e) {
            m.put("maskingRules", List.of());
        }
        return m;
    }

    static Map<String, Object> toMaskingRuleDto(MaskingRuleRecord row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", row.id());
        dto.put("instanceId", row.instanceId());
        dto.put("name", row.name());
        dto.put("strategy", row.strategy());
        dto.put("priority", row.priority());
        dto.put("columnName", row.columnName());
        dto.put("tableName", row.tableName());
        dto.put("namePattern", row.namePattern());
        dto.put("fixedValue", row.fixedValue());
        dto.put("keepPrefix", row.keepPrefix());
        dto.put("keepSuffix", row.keepSuffix());
        dto.put("hashHexLength", row.hashHexLength());
        dto.put("enabled", row.enabled());
        dto.put("createdAt", row.createdAt() != null ? row.createdAt().toString() : null);
        dto.put("updatedAt", row.updatedAt() != null ? row.updatedAt().toString() : null);
        return dto;
    }

    private static MaskingRuleRecord fromBody(String instanceId, MaskingRuleBody body, String ruleId) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        boolean enabled = body.enabled() == null || body.enabled();
        int priority = body.priority() != null ? body.priority() : 0;
        return new MaskingRuleRecord(
                ruleId != null ? ruleId : body.id(),
                instanceId,
                body.name(),
                body.strategy(),
                priority,
                body.columnName(),
                body.tableName(),
                body.namePattern(),
                body.fixedValue(),
                body.keepPrefix(),
                body.keepSuffix(),
                body.hashHexLength(),
                enabled,
                null,
                null);
    }


    private void recordSqlHistory(String instanceId, String sql, boolean ok, Map<String, Object> result) {
        if (sqlHistoryStore == null) {
            return;
        }
        try {
            Long duration = result.get("durationMs") instanceof Number n ? n.longValue() : null;
            Integer rows = result.get("rowCount") instanceof Number n ? n.intValue() : null;
            sqlHistoryStore.insert(instanceId, sql, ok, duration, rows, "console");
        } catch (Exception e) {
            // history must not break execute
        }
    }

    private void recordSqlHistoryFailure(String instanceId, String sql, RuntimeException ex) {
        if (sqlHistoryStore == null) {
            return;
        }
        try {
            sqlHistoryStore.insert(instanceId, sql, false, null, null, "console");
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static Map<String, Object> snippetToMap(SqlSnippetRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("sql", r.sqlText());
        m.put("createdAt", r.createdAt() != null ? r.createdAt().toString() : null);
        m.put("updatedAt", r.updatedAt() != null ? r.updatedAt().toString() : null);
        return m;
    }

    private void audit(String action, String instanceId, Map<String, ?> detail) {
        if (auditService != null) {
            auditService.record(action, instanceId, detail);
        }
    }

    private ConsoleMaskingKeyService requireMaskingKeyService() {
        if (maskingKeyService == null) {
            throw new IllegalStateException("Masking key service is not available");
        }
        return maskingKeyService;
    }
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value : null;
    }
}
