package com.whosly.gateway.runtime;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.console.SupportedDatabaseInfo;
import com.whosly.gateway.console.persist.ConsoleInstanceRecord;
import com.whosly.gateway.console.persist.ConsoleInstanceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.util.Optional;

/**
 * Same-JVM multi-listener runtime: one {@link ProtocolAdapter} per configured
 * creatable {@code gateway.instances[]} entry (or a single synthetic default when
 * the list is empty).
 *
 * <p>Pool / TLS / routing / reset / audit observer stay shared process infrastructure
 * applied via {@link GatewayConfig#buildAdapter}; each listener gets its own
 * {@link GatewayRuntimeMetrics}. Legacy {@code /gateway/*} talks to
 * {@link #getLegacyAdapter()} (proxy-* match, else {@code default}, else first bound).</p>
 */
public class GatewayListenerRuntime implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GatewayListenerRuntime.class);

    public static final String SOURCE_CONFIG = "config";
    public static final String SOURCE_CONSOLE = "console";

    private final Map<String, ManagedListener> listeners = new LinkedHashMap<>();
    private final String legacyId;
    private final GatewayConfig gatewayConfig;
    private final ProtocolAdapterRegistry adapterRegistry;
    private final SupportedDatabaseCatalog catalog;
    private final ConsoleInstanceStore consoleStore; // nullable in map-only test ctor
    private final Object mute = new Object();

    public GatewayListenerRuntime(GatewayConfig gatewayConfig,
                                  ProtocolAdapterRegistry adapterRegistry,
                                  GatewayInstanceProperties instanceProperties,
                                  SupportedDatabaseCatalog catalog) {
        this(gatewayConfig, adapterRegistry, instanceProperties, catalog, null);
    }

    public GatewayListenerRuntime(GatewayConfig gatewayConfig,
                                  ProtocolAdapterRegistry adapterRegistry,
                                  GatewayInstanceProperties instanceProperties,
                                  SupportedDatabaseCatalog catalog,
                                  ConsoleInstanceStore consoleStore) {
        Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        Objects.requireNonNull(adapterRegistry, "adapterRegistry");
        Objects.requireNonNull(instanceProperties, "instanceProperties");
        Objects.requireNonNull(catalog, "catalog");

        this.gatewayConfig = gatewayConfig;
        this.adapterRegistry = adapterRegistry;
        this.catalog = catalog;
        this.consoleStore = consoleStore;

        List<GatewayInstanceProperties.InstanceEntry> yamlEntries =
                resolveEntries(gatewayConfig, instanceProperties);
        failFastOnDuplicatePorts(yamlEntries, gatewayConfig.getProxyPort());

        for (GatewayInstanceProperties.InstanceEntry entry : yamlEntries) {
            ManagedListener managed = createManaged(
                    gatewayConfig, adapterRegistry, catalog, entry, SOURCE_CONFIG);
            if (listeners.put(managed.id(), managed) != null) {
                throw new IllegalStateException(
                        "Duplicate gateway.instances id: '" + managed.id() + "'");
            }
        }

        // Merge console-managed rows from H2 (union; duplicate id/port → fail fast).
        if (consoleStore != null) {
            for (ConsoleInstanceRecord row : consoleStore.findAll()) {
                if (listeners.containsKey(row.id())) {
                    throw new IllegalStateException(
                            "Duplicate instance id between YAML and H2 console store: '"
                                    + row.id() + "'");
                }
                for (ManagedListener existing : listeners.values()) {
                    if (existing.enabled() && row.enabled()
                            && existing.listenPort() == row.listenPort()) {
                        throw new IllegalStateException(
                                "Duplicate listenPort " + row.listenPort()
                                        + " between YAML '" + existing.id()
                                        + "' and H2 console '" + row.id() + "'");
                    }
                }
                GatewayInstanceProperties.InstanceEntry entry = toEntry(row);
                ManagedListener managed = createManaged(
                        gatewayConfig, adapterRegistry, catalog, entry, SOURCE_CONSOLE);
                listeners.put(managed.id(), managed);
            }
        }

        if (listeners.isEmpty()) {
            throw new IllegalStateException("No gateway instances resolved (internal error)");
        }
        this.legacyId = resolveLegacyId(gatewayConfig, listeners);
        log.info("GatewayListenerRuntime ready: {} instance(s), legacyId={} (consoleStore={})",
                listeners.size(), legacyId, consoleStore != null);
    }

    /**
     * Test / programmatic constructor: wrap a pre-built listener map (order preserved).
     * Dynamic add/remove is unavailable (no GatewayConfig wiring).
     */
    public GatewayListenerRuntime(Map<String, ManagedListener> listeners, String legacyId) {
        Objects.requireNonNull(listeners, "listeners");
        if (listeners.isEmpty()) {
            throw new IllegalArgumentException("listeners must not be empty");
        }
        this.gatewayConfig = null;
        this.adapterRegistry = null;
        this.catalog = null;
        this.consoleStore = null;
        this.listeners.putAll(listeners);
        this.legacyId = legacyId != null && listeners.containsKey(legacyId)
                ? legacyId
                : listeners.keySet().iterator().next();
    }

    public List<ManagedListener> list() {
        return List.copyOf(listeners.values());
    }

    public Optional<ManagedListener> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(listeners.get(id.trim()));
    }

    public Optional<ProtocolAdapter> getAdapter(String id) {
        return find(id).map(ManagedListener::adapter).filter(Objects::nonNull);
    }

    public Optional<GatewayRuntimeMetrics> getMetrics(String id) {
        return find(id).map(ManagedListener::metrics);
    }

    public ProtocolAdapter getLegacyAdapter() {
        ManagedListener legacy = listeners.get(legacyId);
        if (legacy == null || legacy.adapter() == null) {
            // Prefer any bound adapter so /gateway/* still works when proxy-* does not match.
            for (ManagedListener listener : listeners.values()) {
                if (listener.adapter() != null) {
                    return listener.adapter();
                }
            }
            throw new IllegalStateException(
                    "No bound ProtocolAdapter available for legacy /gateway/* "
                            + "(configure at least one creatable gateway.instances entry)");
        }
        return legacy.adapter();
    }

    public GatewayRuntimeMetrics getLegacyMetrics() {
        ManagedListener legacy = listeners.get(legacyId);
        if (legacy != null) {
            return legacy.metrics();
        }
        for (ManagedListener listener : listeners.values()) {
            if (listener.adapter() != null) {
                return listener.metrics();
            }
        }
        return listeners.values().iterator().next().metrics();
    }

    public String getLegacyId() {
        return legacyId;
    }

    /**
     * Boot hook: start every enabled creatable listener that has an adapter.
     */
    public void startEnabledOnBoot() {
        for (ManagedListener listener : listeners.values()) {
            if (!listener.enabled() || listener.adapter() == null) {
                continue;
            }
            try {
                startAdapter(listener);
                log.info("Started gateway instance '{}' ({}) on port {}",
                        listener.id(), listener.dbType(), listener.listenPort());
            } catch (RuntimeException e) {
                log.error("Failed to start gateway instance '{}' on boot: {}",
                        listener.id(), e.getMessage());
            }
        }
    }

    public Map<String, Object> start(String id) {
        ManagedListener listener = require(id);
        Map<String, Object> body = baseBody(listener);
        if (!listener.enabled()) {
            body.put("ok", false);
            body.put("message", "实例已禁用（enabled=false）");
            return body;
        }
        if (!listener.creatable() || listener.adapter() == null) {
            body.put("ok", false);
            body.put("message", "数据库类型不可创建：" + listener.dbType());
            return body;
        }
        try {
            ProtocolAdapter adapter = listener.adapter();
            if (!adapter.isRunning()) {
                startAdapter(listener);
                if (!adapter.isRunning()) {
                    body.put("ok", false);
                    body.put("message", "启动失败：监听端口可能被占用（" + listener.listenPort() + "）");
                    body.put("running", false);
                    body.put("status", "STOPPED");
                    return body;
                }
                body.put("message", "实例已启动，监听 "
                        + listener.listenHost() + ":" + listener.listenPort());
            } else {
                body.put("message", "实例已在运行");
            }
            body.put("ok", true);
            body.put("running", adapter.isRunning());
            body.put("status", adapter.isRunning() ? "RUNNING" : "STOPPED");
            return body;
        } catch (Exception e) {
            log.error("Failed to start gateway instance {}", id, e);
            body.put("ok", false);
            body.put("message", "启动失败: " + e.getMessage());
            body.put("running", listener.adapter() != null && listener.adapter().isRunning());
            return body;
        }
    }

    public Map<String, Object> stop(String id) {
        ManagedListener listener = require(id);
        Map<String, Object> body = baseBody(listener);
        if (listener.adapter() == null) {
            body.put("ok", false);
            body.put("message", "实例未绑定 listener（类型不可创建或已禁用）");
            return body;
        }
        try {
            ProtocolAdapter adapter = listener.adapter();
            if (adapter.isRunning()) {
                adapter.stop();
                body.put("message", "实例已停止");
            } else {
                body.put("message", "实例未在运行");
            }
            body.put("ok", true);
            body.put("running", adapter.isRunning());
            body.put("status", adapter.isRunning() ? "RUNNING" : "STOPPED");
            return body;
        } catch (Exception e) {
            log.error("Failed to stop gateway instance {}", id, e);
            body.put("ok", false);
            body.put("message", "停止失败: " + e.getMessage());
            body.put("running", listener.adapter().isRunning());
            return body;
        }
    }

    /**
     * Runtime-register a new instance (console create). Password stays in-memory on the adapter only.
     * Optionally auto-starts when {@code enabled=true}.
     */
    public ManagedListener addInstance(CreateInstanceRequest request) {
        Objects.requireNonNull(request, "request");
        if (gatewayConfig == null || adapterRegistry == null || catalog == null) {
            throw new IllegalStateException("Runtime add requires full GatewayListenerRuntime wiring");
        }
        synchronized (mute) {
            String id = resolveNewId(request.id());
            if (listeners.containsKey(id)) {
                throw new IllegalArgumentException("Instance id already exists: " + id);
            }
            String dbType = normalizeDbType(request.dbType());
            Optional<SupportedDatabaseInfo> typeInfo = catalog.findById(dbType);
            boolean creatable = typeInfo.map(SupportedDatabaseInfo::creatable).orElse(false);
            if (!creatable) {
                throw new IllegalArgumentException("数据库类型不可创建：" + dbType);
            }
            int listenPort = request.listenPort() != null ? request.listenPort() : gatewayConfig.getProxyPort();
            if (listenPort <= 0 || listenPort > 65535) {
                throw new IllegalArgumentException("Invalid listenPort: " + listenPort);
            }
            ensurePortFree(listenPort, id);

            GatewayInstanceProperties.InstanceEntry entry = new GatewayInstanceProperties.InstanceEntry();
            entry.setId(id);
            entry.setName(hasText(request.name()) ? request.name().trim() : id);
            entry.setDbType(dbType);
            entry.setListenHost(hasText(request.listenHost()) ? request.listenHost().trim() : "0.0.0.0");
            entry.setListenPort(listenPort);
            entry.setEnabled(request.enabled() == null || request.enabled());
            entry.setTargetHost(request.targetHost());
            entry.setTargetPort(request.targetPort());
            entry.setTargetDatabase(request.targetDatabase());
            entry.setTargetUsername(request.targetUsername());
            entry.setTargetPassword(request.targetPassword());

            ManagedListener managed = createManaged(
                    gatewayConfig, adapterRegistry, catalog, entry, SOURCE_CONSOLE);
            if (!managed.bound()) {
                throw new IllegalArgumentException("Failed to bind adapter for type: " + dbType);
            }
            if (consoleStore != null) {
                Instant now = Instant.now();
                consoleStore.upsert(new ConsoleInstanceRecord(
                        id,
                        managed.name(),
                        managed.dbType(),
                        managed.listenHost(),
                        managed.listenPort(),
                        managed.enabled(),
                        managed.targetHost(),
                        managed.targetPort(),
                        managed.targetDatabase(),
                        managed.targetUsername(),
                        entry.getTargetPassword(),
                        now,
                        now));
            }
            listeners.put(id, managed);
            log.info("Console-registered gateway instance '{}' ({}) on port {} (source=console, persisted={})",
                    id, dbType, listenPort, consoleStore != null);

            if (managed.enabled()) {
                try {
                    startAdapter(managed);
                    log.info("Auto-started console instance '{}' on port {}", id, listenPort);
                } catch (RuntimeException e) {
                    log.warn("Console instance '{}' registered but auto-start failed: {}",
                            id, e.getMessage());
                }
            }
            return managed;
        }
    }

    /**
     * Stop and remove a <em>runtime-created</em> instance. Config-file instances cannot be deleted.
     */
    public Map<String, Object> removeInstance(String id) {
        synchronized (mute) {
            ManagedListener listener = require(id);
            Map<String, Object> body = baseBody(listener);
            body.put("source", listener.source());
            if (!SOURCE_CONSOLE.equals(listener.source())) {
                body.put("ok", false);
                body.put("message", "YAML/配置实例不可删除（source=config）；仅可停止，或从 application.yml 移除后重启");
                return body;
            }
            if (listener.id().equals(legacyId) && listeners.size() == 1) {
                body.put("ok", false);
                body.put("message", "不能删除唯一遗留实例");
                return body;
            }
            try {
                if (listener.adapter() != null && listener.adapter().isRunning()) {
                    listener.adapter().stop();
                }
            } catch (RuntimeException e) {
                log.warn("Error stopping console instance '{}' before remove: {}", id, e.getMessage());
            }
            if (consoleStore != null) {
                consoleStore.deleteById(listener.id());
            }
            listeners.remove(listener.id());
            body.put("ok", true);
            body.put("message", "管控台实例已删除（H2 + 运行时）");
            body.put("removed", true);
            log.info("Removed console gateway instance '{}' (H2+runtime)", listener.id());
            return body;
        }
    }

    private String resolveNewId(String requested) {
        if (hasText(requested)) {
            return requested.trim();
        }
        String base = "rt-" + Long.toHexString(System.currentTimeMillis());
        String candidate = base;
        int n = 0;
        while (listeners.containsKey(candidate)) {
            candidate = base + "-" + (++n);
        }
        return candidate;
    }

    private void ensurePortFree(int listenPort, String forId) {
        for (ManagedListener existing : listeners.values()) {
            if (existing.enabled() && existing.listenPort() == listenPort) {
                throw new IllegalArgumentException(
                        "listenPort " + listenPort + " already used by instance '" + existing.id()
                                + "' (requested for '" + forId + "')");
            }
        }
    }

    @Override
    public void destroy() {
        for (ManagedListener listener : listeners.values()) {
            ProtocolAdapter adapter = listener.adapter();
            if (adapter != null && adapter.isRunning()) {
                try {
                    adapter.stop();
                } catch (RuntimeException e) {
                    log.warn("Error stopping gateway instance '{}' on shutdown: {}",
                            listener.id(), e.getMessage());
                }
            }
        }
    }

    private static void startAdapter(ManagedListener listener) {
        ProtocolAdapter adapter = listener.adapter();
        if (adapter == null) {
            throw new IllegalStateException("No adapter for instance " + listener.id());
        }
        adapter.start();
    }

    private ManagedListener require(String id) {
        return find(id).orElseThrow(() ->
                new IllegalArgumentException("Unknown gateway instance id: " + id));
    }

    private static Map<String, Object> baseBody(ManagedListener listener) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", listener.id());
        body.put("dbType", listener.dbType());
        body.put("bound", listener.adapter() != null);
        return body;
    }


    private static GatewayInstanceProperties.InstanceEntry toEntry(ConsoleInstanceRecord row) {
        GatewayInstanceProperties.InstanceEntry entry = new GatewayInstanceProperties.InstanceEntry();
        entry.setId(row.id());
        entry.setName(row.name());
        entry.setDbType(row.dbType());
        entry.setListenHost(row.listenHost());
        entry.setListenPort(row.listenPort());
        entry.setEnabled(row.enabled());
        entry.setTargetHost(row.targetHost());
        entry.setTargetPort(row.targetPort());
        entry.setTargetDatabase(row.targetDatabase());
        entry.setTargetUsername(row.targetUsername());
        entry.setTargetPassword(row.targetPassword());
        return entry;
    }

    private static ManagedListener createManaged(GatewayConfig gatewayConfig,
                                                 ProtocolAdapterRegistry adapterRegistry,
                                                 SupportedDatabaseCatalog catalog,
                                                 GatewayInstanceProperties.InstanceEntry entry,
                                                 String source) {
        String id = hasText(entry.getId()) ? entry.getId().trim() : "unnamed";
        String dbType = normalizeDbType(hasText(entry.getDbType())
                ? entry.getDbType()
                : gatewayConfig.getProxyDbType());
        String name = hasText(entry.getName()) ? entry.getName().trim() : id;
        String listenHost = hasText(entry.getListenHost()) ? entry.getListenHost().trim() : "0.0.0.0";
        int listenPort = entry.getListenPort() != null ? entry.getListenPort() : gatewayConfig.getProxyPort();

        String targetHost = firstNonBlank(entry.getTargetHost(), gatewayConfig.getTargetHost());
        int targetPort = entry.getTargetPort() != null ? entry.getTargetPort() : gatewayConfig.getTargetPort();
        String targetDatabase = firstNonBlank(entry.getTargetDatabase(), gatewayConfig.getTargetDatabase());
        String targetUsername = firstNonBlank(entry.getTargetUsername(), gatewayConfig.getTargetUsername());
        String targetPassword = firstNonBlank(entry.getTargetPassword(), gatewayConfig.getTargetPassword());
        boolean passwordConfigured = hasText(targetPassword);

        Optional<SupportedDatabaseInfo> typeInfo = catalog.findById(dbType);
        boolean creatable = typeInfo.map(SupportedDatabaseInfo::creatable).orElseGet(() -> {
            try {
                adapterRegistry.create(dbType);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });

        ProtocolAdapter adapter = null;
        GatewayRuntimeMetrics metrics = new GatewayRuntimeMetrics();
        if (entry.isEnabled() && creatable) {
            adapter = gatewayConfig.buildAdapter(
                    adapterRegistry,
                    dbType,
                    listenPort,
                    targetHost,
                    targetPort,
                    targetUsername,
                    targetPassword,
                    targetDatabase,
                    metrics);
        }

        return new ManagedListener(
                id,
                name,
                dbType,
                listenHost,
                listenPort,
                entry.isEnabled(),
                creatable,
                passwordConfigured,
                targetHost,
                targetPort,
                targetDatabase,
                targetUsername,
                adapter,
                metrics,
                source != null ? source : SOURCE_CONFIG
        );
    }

    private static List<GatewayInstanceProperties.InstanceEntry> resolveEntries(
            GatewayConfig gatewayConfig,
            GatewayInstanceProperties instanceProperties) {
        List<GatewayInstanceProperties.InstanceEntry> configured = instanceProperties.getInstances();
        if (configured != null && !configured.isEmpty()) {
            return List.copyOf(configured);
        }
        return List.of(synthesizeDefaultEntry(gatewayConfig));
    }

    static GatewayInstanceProperties.InstanceEntry synthesizeDefaultEntry(GatewayConfig gatewayConfig) {
        GatewayInstanceProperties.InstanceEntry entry = new GatewayInstanceProperties.InstanceEntry();
        entry.setId("default");
        entry.setName("默认网关实例");
        entry.setDbType(gatewayConfig.getProxyDbType());
        entry.setListenHost("0.0.0.0");
        entry.setListenPort(gatewayConfig.getProxyPort());
        entry.setEnabled(true);
        entry.setTargetHost(gatewayConfig.getTargetHost());
        entry.setTargetPort(gatewayConfig.getTargetPort());
        entry.setTargetDatabase(gatewayConfig.getTargetDatabase());
        entry.setTargetUsername(gatewayConfig.getTargetUsername());
        // Password inherited at build time from gatewayConfig; do not copy into entry logs.
        return entry;
    }

    private static void failFastOnDuplicatePorts(List<GatewayInstanceProperties.InstanceEntry> entries,
                                                   int defaultProxyPort) {
        Map<Integer, String> ports = new LinkedHashMap<>();
        for (GatewayInstanceProperties.InstanceEntry entry : entries) {
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            String id = hasText(entry.getId()) ? entry.getId().trim() : "unnamed";
            int port = entry.getListenPort() != null ? entry.getListenPort() : defaultProxyPort;
            String previous = ports.put(port, id);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate gateway.instances listenPort " + port
                                + " for '" + id + "' and '" + previous + "' "
                                + "(each instance needs a distinct listen port)");
            }
        }
    }

    private static String resolveLegacyId(GatewayConfig gatewayConfig,
                                          Map<String, ManagedListener> listeners) {
        String processType = normalizeDbType(gatewayConfig.getProxyDbType());
        int processPort = gatewayConfig.getProxyPort();
        for (ManagedListener listener : listeners.values()) {
            if (listener.adapter() != null
                    && listener.dbType().equals(processType)
                    && listener.listenPort() == processPort) {
                return listener.id();
            }
        }
        if (listeners.containsKey("default") && listeners.get("default").adapter() != null) {
            return "default";
        }
        for (ManagedListener listener : listeners.values()) {
            if (listener.adapter() != null) {
                return listener.id();
            }
        }
        return listeners.keySet().iterator().next();
    }

    static String normalizeDbType(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            return "mysql";
        }
        String normalized = dbType.toLowerCase(Locale.ROOT).trim();
        if ("postgres".equals(normalized)) {
            return "postgresql";
        }
        if ("mssql".equals(normalized)) {
            return "sqlserver";
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : (fallback != null ? fallback : "");
    }


    /**
     * Console create payload (password never echoed back on responses).
     */
    public record CreateInstanceRequest(
            String id,
            String name,
            String dbType,
            String listenHost,
            Integer listenPort,
            String targetHost,
            Integer targetPort,
            String targetDatabase,
            String targetUsername,
            String targetPassword,
            Boolean enabled
    ) {
    }

    /**
     * One configured (or synthetic) listener slot and its optional bound adapter.
     */
    public record ManagedListener(
            String id,
            String name,
            String dbType,
            String listenHost,
            int listenPort,
            boolean enabled,
            boolean creatable,
            boolean passwordConfigured,
            String targetHost,
            int targetPort,
            String targetDatabase,
            String targetUsername,
            ProtocolAdapter adapter,
            GatewayRuntimeMetrics metrics,
            String source
    ) {
        public ManagedListener {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dbType, "dbType");
            Objects.requireNonNull(listenHost, "listenHost");
            Objects.requireNonNull(metrics, "metrics");
            targetHost = targetHost != null ? targetHost : "";
            targetDatabase = targetDatabase != null ? targetDatabase : "";
            targetUsername = targetUsername != null ? targetUsername : "";
            source = (source != null && !source.isBlank()) ? source : SOURCE_CONFIG;
        }

        /** True when created via console POST and persisted in H2 (not from application.yml). */
        public boolean consoleCreated() {
            return SOURCE_CONSOLE.equals(source);
        }

        public boolean bound() {
            return adapter != null;
        }

        public boolean isRunning() {
            return adapter != null && adapter.isRunning();
        }

        public int activeSessions() {
            return adapter != null ? adapter.getActiveSessions().size() : 0;
        }

        public Integer activeConnections() {
            if (adapter instanceof AbstractProtocolAdapter abstractAdapter) {
                return abstractAdapter.getActiveConnectionCount();
            }
            return null;
        }

        public Integer maxConnections() {
            if (adapter instanceof AbstractProtocolAdapter abstractAdapter) {
                return abstractAdapter.getMaxConnections();
            }
            return null;
        }
    }
}
