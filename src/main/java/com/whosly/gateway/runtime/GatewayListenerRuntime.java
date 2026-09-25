package com.whosly.gateway.runtime;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.runtime.spi.DatabaseTypeInfo;
import com.whosly.gateway.runtime.spi.InstanceCatalog;
import com.whosly.gateway.runtime.spi.InstanceMaskingSupport;
import com.whosly.gateway.runtime.spi.PersistedInstance;
import com.whosly.gateway.runtime.spi.PersistedInstanceStore;
import com.whosly.gateway.runtime.spi.TrafficRingAttachment;
import com.whosly.gateway.adapter.protocol.DatabaseTrafficObserver;
import com.whosly.gateway.masking.MaskingEngine;
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
    private final InstanceCatalog catalog;
    private final PersistedInstanceStore consoleStore; // nullable in map-only test ctor
    private final InstanceMaskingSupport maskingSupport; // nullable
    private final TrafficRingAttachment trafficRingAttachment; // nullable
    private final Object mute = new Object();

    public GatewayListenerRuntime(GatewayConfig gatewayConfig,
                                  ProtocolAdapterRegistry adapterRegistry,
                                  GatewayInstanceProperties instanceProperties,
                                  InstanceCatalog catalog) {
        this(gatewayConfig, adapterRegistry, instanceProperties, catalog, null, null, null);
    }

    public GatewayListenerRuntime(GatewayConfig gatewayConfig,
                                  ProtocolAdapterRegistry adapterRegistry,
                                  GatewayInstanceProperties instanceProperties,
                                  InstanceCatalog catalog,
                                  PersistedInstanceStore consoleStore) {
        this(gatewayConfig, adapterRegistry, instanceProperties, catalog, consoleStore, null, null);
    }

    public GatewayListenerRuntime(GatewayConfig gatewayConfig,
                                  ProtocolAdapterRegistry adapterRegistry,
                                  GatewayInstanceProperties instanceProperties,
                                  InstanceCatalog catalog,
                                  PersistedInstanceStore consoleStore,
                                  InstanceMaskingSupport maskingSupport) {
        this(gatewayConfig, adapterRegistry, instanceProperties, catalog, consoleStore,
                maskingSupport, null);
    }

    public GatewayListenerRuntime(GatewayConfig gatewayConfig,
                                  ProtocolAdapterRegistry adapterRegistry,
                                  GatewayInstanceProperties instanceProperties,
                                  InstanceCatalog catalog,
                                  PersistedInstanceStore consoleStore,
                                  InstanceMaskingSupport maskingSupport,
                                  TrafficRingAttachment trafficRingAttachment) {
        Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        Objects.requireNonNull(adapterRegistry, "adapterRegistry");
        Objects.requireNonNull(instanceProperties, "instanceProperties");
        Objects.requireNonNull(catalog, "catalog");

        this.gatewayConfig = gatewayConfig;
        this.adapterRegistry = adapterRegistry;
        this.catalog = catalog;
        this.consoleStore = consoleStore;
        this.maskingSupport = maskingSupport;
        this.trafficRingAttachment = trafficRingAttachment;

        List<GatewayInstanceProperties.InstanceEntry> yamlEntries =
                resolveEntries(gatewayConfig, instanceProperties);
        failFastOnDuplicatePorts(yamlEntries, gatewayConfig.getProxyPort());

        for (GatewayInstanceProperties.InstanceEntry entry : yamlEntries) {
            ManagedListener managed = createManaged(entry, SOURCE_CONFIG);
            if (listeners.put(managed.id(), managed) != null) {
                throw new IllegalStateException(
                        "Duplicate gateway.instances id: '" + managed.id() + "'");
            }
        }

        // Merge console-managed rows from H2 (union; duplicate id/port → fail fast).
        if (consoleStore != null) {
            for (PersistedInstance row : consoleStore.findAll()) {
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
                ManagedListener managed = createManaged(entry, SOURCE_CONSOLE);
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
        this.maskingSupport = null;
        this.trafficRingAttachment = null;
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
            Optional<DatabaseTypeInfo> typeInfo = catalog.lookup(dbType);
            boolean creatable = typeInfo.map(DatabaseTypeInfo::creatable).orElse(false);
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

            ManagedListener managed = createManaged(entry, SOURCE_CONSOLE);
            if (!managed.bound()) {
                throw new IllegalArgumentException("Failed to bind adapter for type: " + dbType);
            }
            if (consoleStore != null) {
                Instant now = Instant.now();
                consoleStore.upsert(new PersistedInstance(
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
            if (maskingSupport != null) {
                int removedRules = maskingSupport.deleteRulesByInstanceId(listener.id());
                if (removedRules > 0) {
                    log.info("Cascaded delete of {} masking rule(s) for instance '{}'",
                            removedRules, listener.id());
                }
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

    /**
     * Update a console-sourced instance. Config-file instances are rejected.
     * If the listener was running, stop → rebuild adapter → start (same id).
     * Omitting/blank {@code targetPassword} keeps the existing stored password.
     */
    public ManagedListener updateInstance(String id, UpdateInstanceRequest request) {
        Objects.requireNonNull(request, "request");
        if (gatewayConfig == null || adapterRegistry == null || catalog == null) {
            throw new IllegalStateException("Runtime update requires full GatewayListenerRuntime wiring");
        }
        synchronized (mute) {
            ManagedListener existing = require(id);
            if (!SOURCE_CONSOLE.equals(existing.source())) {
                throw new IllegalArgumentException(
                        "YAML/配置实例不可编辑（source=config）；请先克隆为管控台实例，或仅使用停止");
            }
            String name = hasText(request.name()) ? request.name().trim() : existing.name();
            String listenHost = hasText(request.listenHost()) ? request.listenHost().trim() : existing.listenHost();
            int listenPort = request.listenPort() != null ? request.listenPort() : existing.listenPort();
            if (listenPort <= 0 || listenPort > 65535) {
                throw new IllegalArgumentException("Invalid listenPort: " + listenPort);
            }
            ensurePortFree(listenPort, existing.id());

            String targetHost = request.targetHost() != null
                    ? request.targetHost().trim() : existing.targetHost();
            if (!hasText(targetHost)) {
                throw new IllegalArgumentException("targetHost is required");
            }
            int targetPort = request.targetPort() != null ? request.targetPort() : existing.targetPort();
            String targetDatabase = request.targetDatabase() != null
                    ? request.targetDatabase() : existing.targetDatabase();
            String targetUsername = request.targetUsername() != null
                    ? request.targetUsername() : existing.targetUsername();
            boolean enabled = request.enabled() != null ? request.enabled() : existing.enabled();

            String password = resolveExistingPassword(existing);
            if (hasText(request.targetPassword())) {
                password = request.targetPassword();
            }

            boolean wasRunning = existing.isRunning();
            try {
                if (existing.adapter() != null && existing.adapter().isRunning()) {
                    existing.adapter().stop();
                }
            } catch (RuntimeException e) {
                log.warn("Error stopping instance '{}' before update: {}", id, e.getMessage());
            }

            GatewayInstanceProperties.InstanceEntry entry = new GatewayInstanceProperties.InstanceEntry();
            entry.setId(existing.id());
            entry.setName(name);
            entry.setDbType(existing.dbType()); // immutable
            entry.setListenHost(listenHost);
            entry.setListenPort(listenPort);
            entry.setEnabled(enabled);
            entry.setTargetHost(targetHost);
            entry.setTargetPort(targetPort);
            entry.setTargetDatabase(targetDatabase);
            entry.setTargetUsername(targetUsername);
            entry.setTargetPassword(password);

            ManagedListener managed = createManaged(entry, SOURCE_CONSOLE);
            if (consoleStore != null) {
                Instant created = consoleStore.findById(existing.id())
                        .map(PersistedInstance::createdAt)
                        .orElse(Instant.now());
                consoleStore.upsert(new PersistedInstance(
                        existing.id(),
                        managed.name(),
                        managed.dbType(),
                        managed.listenHost(),
                        managed.listenPort(),
                        managed.enabled(),
                        managed.targetHost(),
                        managed.targetPort(),
                        managed.targetDatabase(),
                        managed.targetUsername(),
                        password,
                        created,
                        Instant.now()));
            }
            listeners.put(existing.id(), managed);
            log.info("Updated console instance '{}' (listen={}:{}, rebound={}, wasRunning={})",
                    existing.id(), listenHost, listenPort, managed.bound(), wasRunning);

            if (wasRunning && managed.enabled() && managed.bound()) {
                try {
                    startAdapter(managed);
                } catch (RuntimeException e) {
                    log.warn("Instance '{}' updated but restart failed: {}", existing.id(), e.getMessage());
                }
            }
            return managed;
        }
    }

    /**
     * Clone any instance into a new console-sourced instance (import into H2).
     * Prefers copying the password from store for lab continuity; API never echoes it.
     */
    public ManagedListener cloneInstance(String sourceId, CloneInstanceRequest request) {
        if (request == null) {
            request = new CloneInstanceRequest(null, null, null, true);
        }
        // Resolve source outside nested addInstance sync by calling require first;
        // addInstance takes mute again — Java intrinsic locks are reentrant on same thread.
        ManagedListener source = require(sourceId);
        String newId;
        int listenPort;
        String name;
        String password;
        synchronized (mute) {
            newId = resolveNewId(request.id());
            if (listeners.containsKey(newId)) {
                throw new IllegalArgumentException("Instance id already exists: " + newId);
            }
            listenPort = allocateListenPort(request.listenPort(), source.listenPort(), newId);
            name = hasText(request.name())
                    ? request.name().trim()
                    : source.name() + " (克隆)";
            password = resolveExistingPassword(source);
        }

        CreateInstanceRequest create = new CreateInstanceRequest(
                newId,
                name,
                source.dbType(),
                source.listenHost(),
                listenPort,
                source.targetHost(),
                source.targetPort(),
                source.targetDatabase(),
                source.targetUsername(),
                password,
                true); // enable to bind adapter; stop immediately after create
        ManagedListener managed = addInstance(create);
        try {
            stop(managed.id());
        } catch (RuntimeException e) {
            log.warn("Clone '{}' bound but stop failed: {}", managed.id(), e.getMessage());
        }
        managed = find(managed.id()).orElse(managed);

        boolean copyRules = request.copyMaskingRules() == null || request.copyMaskingRules();
        if (copyRules && maskingSupport != null) {
            maskingSupport.copyRules(source.id(), managed.id());
            reloadMasking(managed.id());
        }
        log.info("Cloned instance '{}' -> '{}' (source={}, maskingCopied={})",
                sourceId, managed.id(), source.source(), copyRules);
        return managed;
    }

    /**
     * Import instances from export-shaped maps into console H2.
     * Passwords in JSON are ignored; new rows have no password until edited.
     */
    public Map<String, Object> importInstances(List<Map<String, Object>> instances,
                                               boolean replace,
                                               boolean skipExisting) {
        if (instances == null) {
            throw new IllegalArgumentException("instances array is required");
        }
        int created = 0;
        int skipped = 0;
        int failed = 0;
        List<Map<String, Object>> details = new java.util.ArrayList<>();
        for (Map<String, Object> raw : instances) {
            Map<String, Object> one = new LinkedHashMap<>();
            try {
                if (raw == null) {
                    throw new IllegalArgumentException("null instance entry");
                }
                String id = stringVal(raw.get("id"));
                String dbType = stringVal(raw.get("dbType"));
                if (!hasText(dbType)) {
                    throw new IllegalArgumentException("dbType is required");
                }
                Integer listenPort = intVal(raw.get("listenPort"));
                if (listenPort == null) {
                    throw new IllegalArgumentException("listenPort is required");
                }
                String targetHost = stringVal(raw.get("targetHost"));
                if (!hasText(targetHost)) {
                    throw new IllegalArgumentException("targetHost is required");
                }
                Integer targetPort = intVal(raw.get("targetPort"));
                if (targetPort == null) {
                    throw new IllegalArgumentException("targetPort is required");
                }

                if (hasText(id) && listeners.containsKey(id.trim())) {
                    ManagedListener existing = listeners.get(id.trim());
                    if (replace) {
                        if (!SOURCE_CONSOLE.equals(existing.source())) {
                            throw new IllegalArgumentException(
                                    "不能覆盖配置文件实例 id=" + id + "（请换 id 或 skipExisting）");
                        }
                        removeInstance(id.trim());
                    } else if (skipExisting) {
                        one.put("id", id);
                        one.put("ok", true);
                        one.put("action", "skipped");
                        one.put("message", "id 已存在，已跳过");
                        details.add(one);
                        skipped++;
                        continue;
                    } else {
                        throw new IllegalArgumentException("Instance id already exists: " + id);
                    }
                }

                CreateInstanceRequest req = new CreateInstanceRequest(
                        hasText(id) ? id.trim() : null,
                        hasText(stringVal(raw.get("name"))) ? stringVal(raw.get("name"))
                                : (hasText(id) ? id : "imported"),
                        dbType,
                        hasText(stringVal(raw.get("listenHost"))) ? stringVal(raw.get("listenHost")) : "0.0.0.0",
                        listenPort,
                        targetHost,
                        targetPort,
                        stringVal(raw.get("targetDatabase")),
                        stringVal(raw.get("targetUsername")),
                        null, // never import passwords
                        raw.get("enabled") instanceof Boolean b ? b : true);
                ManagedListener managed = addInstance(req);
                one.put("id", managed.id());
                one.put("ok", true);
                one.put("action", "created");
                one.put("passwordConfigured", managed.passwordConfigured());
                details.add(one);
                created++;
            } catch (RuntimeException e) {
                one.put("ok", false);
                one.put("action", "failed");
                one.put("message", e.getMessage());
                if (raw != null && raw.get("id") != null) {
                    one.put("id", String.valueOf(raw.get("id")));
                }
                details.add(one);
                failed++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", failed == 0);
        body.put("created", created);
        body.put("skipped", skipped);
        body.put("failed", failed);
        body.put("results", details);
        body.put("message", "导入完成：创建 " + created + "，跳过 " + skipped + "，失败 " + failed);
        return body;
    }

    private String resolveExistingPassword(ManagedListener listener) {
        if (consoleStore != null && SOURCE_CONSOLE.equals(listener.source())) {
            Optional<PersistedInstance> row = consoleStore.findById(listener.id());
            if (row.isPresent() && hasText(row.get().targetPassword())) {
                return row.get().targetPassword();
            }
        }
        if (gatewayConfig != null && hasText(gatewayConfig.getTargetPassword())) {
            return gatewayConfig.getTargetPassword();
        }
        return null;
    }

    private int allocateListenPort(Integer requested, int preferNear, String forId) {
        if (requested != null) {
            if (requested <= 0 || requested > 65535) {
                throw new IllegalArgumentException("Invalid listenPort: " + requested);
            }
            ensurePortFree(requested, forId);
            return requested;
        }
        int start = preferNear > 0 && preferNear < 65535 ? preferNear + 1 : 33000;
        for (int i = 0; i < 2000; i++) {
            int p = start + i;
            if (p > 65535) {
                p = 10000 + ((start + i) % 50000);
            }
            try {
                ensurePortFree(p, forId);
                return p;
            } catch (IllegalArgumentException ignored) {
                // try next
            }
        }
        throw new IllegalStateException("无法分配空闲 listenPort");
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Integer intVal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        return Integer.parseInt(s);
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
            if (forId != null && existing.id().equals(forId)) {
                continue;
            }
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


    private static GatewayInstanceProperties.InstanceEntry toEntry(PersistedInstance row) {
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

    private ManagedListener createManaged(GatewayInstanceProperties.InstanceEntry entry,
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
        // Console instances do not silently inherit process YAML password (import/edit clarity).
        String targetPassword = SOURCE_CONSOLE.equals(source)
                ? (entry.getTargetPassword() != null ? entry.getTargetPassword() : "")
                : firstNonBlank(entry.getTargetPassword(), gatewayConfig.getTargetPassword());
        boolean passwordConfigured = hasText(targetPassword);

        Optional<DatabaseTypeInfo> typeInfo = catalog.lookup(dbType);
        boolean creatable = typeInfo.map(DatabaseTypeInfo::creatable).orElseGet(() -> {
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
            attachRecentTraffic(id, adapter);
            applyInstanceMasking(id, adapter);
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

    /**
     * Rebuild this instance's {@link MaskingEngine} from H2 (+ global beans) and
     * hot-swap onto the live adapter <em>without</em> stopping the TCP listener.
     *
     * <p>New sessions pick up the new engine immediately. In-flight sessions keep
     * the engine captured at session start until they reconnect.</p>
     */
    /** Hot-reload MaskingEngine for every bound instance (e.g. after masking-key change). */
    public Map<String, Object> reloadAllMasking() {
        int ok = 0;
        int skipped = 0;
        int failed = 0;
        java.util.List<Map<String, Object>> failures = new java.util.ArrayList<>();
        for (ManagedListener listener : list()) {
            if (listener.adapter() == null) {
                skipped++;
                continue;
            }
            Map<String, Object> one = reloadMasking(listener.id());
            if (Boolean.TRUE.equals(one.get("ok"))) {
                ok++;
            } else if (one.containsKey("error")) {
                // compile/load failure (e.g. encrypt without key) — fail closed
                failed++;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("instanceId", listener.id());
                f.put("message", one.get("message"));
                failures.add(f);
            } else {
                skipped++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", failed == 0);
        body.put("reloaded", ok);
        body.put("skipped", skipped);
        body.put("failed", failed);
        if (!failures.isEmpty()) {
            body.put("failures", failures);
        }
        body.put("message", failed == 0
                ? "已热重载 " + ok + " 个实例的脱敏引擎"
                : "热重载完成：成功 " + ok + "，失败 " + failed + "（失败闭合，未静默跳过 encrypt）");
        log.info("reloadAllMasking reloaded={} skipped={} failed={}", ok, skipped, failed);
        return body;
    }

    public Map<String, Object> reloadMasking(String id) {
        ManagedListener listener = require(id);
        Map<String, Object> body = baseBody(listener);
        if (listener.adapter() == null) {
            body.put("ok", false);
            body.put("message", "实例未绑定 adapter，无法挂载脱敏引擎");
            body.put("reloaded", false);
            return body;
        }
        if (!(listener.adapter() instanceof AbstractProtocolAdapter abstractAdapter)) {
            body.put("ok", false);
            body.put("message", "adapter 不支持 setMaskingEngine");
            body.put("reloaded", false);
            return body;
        }
        try {
            MaskingEngine engine = buildMaskingEngine(listener.id());
            abstractAdapter.setMaskingEngine(engine);
            body.put("ok", true);
            body.put("reloaded", true);
            body.put("maskingActive", engine.isActive());
            body.put("listenerBounced", false);
            body.put("message", engine.isActive()
                    ? "已热更新脱敏引擎（不停端口；已有会话保持旧规则至重连）"
                    : "已热更新：当前无启用规则（透明转发）");
            log.info("Reloaded MaskingEngine for instance '{}' (active={})",
                    listener.id(), engine.isActive());
            return body;
        } catch (IllegalArgumentException e) {
            body.put("ok", false);
            body.put("reloaded", false);
            body.put("maskingActive", false);
            body.put("listenerBounced", false);
            body.put("message", e.getMessage());
            body.put("error", e.getMessage());
            log.warn("Failed to reload MaskingEngine for instance '{}': {}", listener.id(), e.getMessage());
            return body;
        }
    }


    /**
     * Compose instance-tagged {@link TrafficRingAttachment} in front of the existing observer
     * (audit/masking). Fail-open ring must not break mandatory audit delivery.
     */
    private void attachRecentTraffic(String instanceId, ProtocolAdapter adapter) {
        if (trafficRingAttachment == null || !(adapter instanceof AbstractProtocolAdapter apa)) {
            return;
        }
        DatabaseTrafficObserver prior = apa.getDatabaseTrafficObserver();
        // Mask literals before the ring (and independently of audit spool masking).
        DatabaseTrafficObserver maskedRing =
                DatabaseTrafficObserver.masking(trafficRingAttachment.forInstance(instanceId));
        apa.setDatabaseTrafficObserver(DatabaseTrafficObserver.compose(
                maskedRing,
                prior != null ? prior : DatabaseTrafficObserver.noop()));
    }

    private void applyInstanceMasking(String instanceId, ProtocolAdapter adapter) {
        if (!(adapter instanceof AbstractProtocolAdapter abstractAdapter)) {
            return;
        }
        abstractAdapter.setMaskingEngine(buildMaskingEngine(instanceId));
    }

    private MaskingEngine buildMaskingEngine(String instanceId) {
        if (maskingSupport != null) {
            return maskingSupport.buildFor(instanceId);
        }
        // Fallback: process-level bean from GatewayConfig (tests without factory).
        if (gatewayConfig != null) {
            return gatewayConfig.maskingEngine();
        }
        return MaskingEngine.inactive();
    }

    public Optional<InstanceMaskingSupport> maskingSupport() {
        return Optional.ofNullable(maskingSupport);
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

    /** Console update payload; {@code dbType}/{@code id} are not updatable. */
    public record UpdateInstanceRequest(
            String name,
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

    /** Clone options; omitted listenPort → auto-allocate. */
    public record CloneInstanceRequest(
            String id,
            String name,
            Integer listenPort,
            Boolean copyMaskingRules
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
