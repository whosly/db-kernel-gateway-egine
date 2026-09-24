package com.whosly.gateway.console;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.config.GatewayInstanceProperties;
import com.whosly.gateway.console.GatewayInstance.InstanceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protocol-agnostic multi-instance registry for the console.
 *
 * <p>MVP runtime still binds at most one {@link ProtocolAdapter} (process-level).
 * Configured instances that do not match the bound adapter surface as
 * {@link InstanceStatus#UNBOUND} so dual-listener can plug in next.</p>
 */
@Service
public class GatewayInstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(GatewayInstanceRegistry.class);

    private final GatewayInstanceProperties instanceProperties;
    private final GatewayConfig gatewayConfig;
    private final SupportedDatabaseCatalog catalog;
    private final ProtocolAdapter protocolAdapter;
    private final GatewayRuntimeMetrics runtimeMetrics;

    public GatewayInstanceRegistry(GatewayInstanceProperties instanceProperties,
                                   GatewayConfig gatewayConfig,
                                   SupportedDatabaseCatalog catalog,
                                   ProtocolAdapter protocolAdapter,
                                   GatewayRuntimeMetrics runtimeMetrics) {
        this.instanceProperties = Objects.requireNonNull(instanceProperties, "instanceProperties");
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.protocolAdapter = Objects.requireNonNull(protocolAdapter, "protocolAdapter");
        this.runtimeMetrics = runtimeMetrics != null ? runtimeMetrics : GatewayRuntimeMetrics.noop();
    }

    public List<GatewayInstance> listInstances() {
        List<GatewayInstanceProperties.InstanceEntry> entries = configuredEntries();
        List<GatewayInstance> out = new ArrayList<>(entries.size());
        for (GatewayInstanceProperties.InstanceEntry entry : entries) {
            out.add(toInstance(entry));
        }
        return List.copyOf(out);
    }

    public Optional<GatewayInstance> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String key = id.trim();
        return listInstances().stream().filter(i -> i.id().equals(key)).findFirst();
    }

    public Map<String, Object> start(String id) {
        GatewayInstance instance = require(id);
        Map<String, Object> body = baseActionBody(instance);
        if (!instance.enabled()) {
            body.put("ok", false);
            body.put("message", "实例已禁用（enabled=false）");
            return body;
        }
        if (instance.status() == InstanceStatus.UNSUPPORTED) {
            body.put("ok", false);
            body.put("message", "数据库类型不可创建：" + instance.dbType());
            return body;
        }
        if (!instance.bound()) {
            body.put("ok", false);
            body.put("message", "实例未绑定本进程 listener（多监听运行时尚未落地）；仅 bound 实例可启停");
            return body;
        }
        try {
            if (!protocolAdapter.isRunning()) {
                protocolAdapter.start();
                body.put("message", "实例已启动，监听 " + instance.listenHost() + ":" + instance.listenPort());
            } else {
                body.put("message", "实例已在运行");
            }
            body.put("ok", true);
            body.put("running", protocolAdapter.isRunning());
            body.put("status", protocolAdapter.isRunning() ? InstanceStatus.RUNNING.name() : InstanceStatus.STOPPED.name());
            return body;
        } catch (Exception e) {
            log.error("Failed to start gateway instance {}", id, e);
            body.put("ok", false);
            body.put("message", "启动失败: " + e.getMessage());
            body.put("running", protocolAdapter.isRunning());
            return body;
        }
    }

    public Map<String, Object> stop(String id) {
        GatewayInstance instance = require(id);
        Map<String, Object> body = baseActionBody(instance);
        if (!instance.bound()) {
            body.put("ok", false);
            body.put("message", "实例未绑定本进程 listener；仅 bound 实例可启停");
            return body;
        }
        try {
            if (protocolAdapter.isRunning()) {
                protocolAdapter.stop();
                body.put("message", "实例已停止");
            } else {
                body.put("message", "实例未在运行");
            }
            body.put("ok", true);
            body.put("running", protocolAdapter.isRunning());
            body.put("status", protocolAdapter.isRunning() ? InstanceStatus.RUNNING.name() : InstanceStatus.STOPPED.name());
            return body;
        } catch (Exception e) {
            log.error("Failed to stop gateway instance {}", id, e);
            body.put("ok", false);
            body.put("message", "停止失败: " + e.getMessage());
            body.put("running", protocolAdapter.isRunning());
            return body;
        }
    }

    public Map<String, Object> statusOf(String id) {
        GatewayInstance instance = require(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", instance.id());
        body.put("name", instance.name());
        body.put("dbType", instance.dbType());
        body.put("status", instance.status().name());
        body.put("bound", instance.bound());
        body.put("listenHost", instance.listenHost());
        body.put("listenPort", instance.listenPort());
        body.put("enabled", instance.enabled());
        body.put("activeSessions", instance.activeSessions());
        body.put("activeConnections", instance.activeConnections());
        body.put("maxConnections", instance.maxConnections());
        body.put("message", instance.message());
        return body;
    }

    public Map<String, Object> metricsOf(String id) {
        GatewayInstance instance = require(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", instance.id());
        body.put("dbType", instance.dbType());
        body.put("bound", instance.bound());
        body.put("status", instance.status().name());
        body.put("metrics", instance.metrics());
        return body;
    }

    private GatewayInstance require(String id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + id));
    }

    private Map<String, Object> baseActionBody(GatewayInstance instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", instance.id());
        body.put("dbType", instance.dbType());
        body.put("bound", instance.bound());
        return body;
    }

    private List<GatewayInstanceProperties.InstanceEntry> configuredEntries() {
        List<GatewayInstanceProperties.InstanceEntry> configured = instanceProperties.getInstances();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        return List.of(synthesizeDefaultEntry());
    }

    /**
     * When {@code gateway.instances} is empty, expose the process-level proxy as one instance.
     */
    private GatewayInstanceProperties.InstanceEntry synthesizeDefaultEntry() {
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
        return entry;
    }

    private GatewayInstance toInstance(GatewayInstanceProperties.InstanceEntry entry) {
        String id = hasText(entry.getId()) ? entry.getId().trim() : "unnamed";
        String dbType = hasText(entry.getDbType())
                ? entry.getDbType().toLowerCase(Locale.ROOT).trim()
                : gatewayConfig.getProxyDbType().toLowerCase(Locale.ROOT).trim();
        String name = hasText(entry.getName()) ? entry.getName().trim() : id;
        String listenHost = hasText(entry.getListenHost()) ? entry.getListenHost().trim() : "0.0.0.0";
        int listenPort = entry.getListenPort() != null ? entry.getListenPort() : gatewayConfig.getProxyPort();

        String targetHost = firstNonBlank(entry.getTargetHost(), gatewayConfig.getTargetHost());
        int targetPort = entry.getTargetPort() != null ? entry.getTargetPort() : gatewayConfig.getTargetPort();
        String targetDatabase = firstNonBlank(entry.getTargetDatabase(), gatewayConfig.getTargetDatabase());
        String targetUsername = firstNonBlank(entry.getTargetUsername(), gatewayConfig.getTargetUsername());
        boolean passwordConfigured = hasText(gatewayConfig.getTargetPassword());

        Optional<SupportedDatabaseInfo> typeInfo = catalog.findById(dbType);
        boolean creatable = typeInfo.map(SupportedDatabaseInfo::creatable).orElse(false);
        boolean consoleCreateAllowed = typeInfo.map(SupportedDatabaseInfo::consoleCreateAllowed).orElse(false);

        boolean bound = isBound(dbType, listenPort);
        InstanceStatus status;
        String message;
        Integer activeSessions = null;
        Integer activeConnections = null;
        Integer maxConnections = null;
        Map<String, Long> metrics = Map.of();
        boolean startable = false;

        if (!entry.isEnabled()) {
            status = InstanceStatus.DISABLED;
            message = "配置未启用";
        } else if (!creatable || !consoleCreateAllowed) {
            status = InstanceStatus.UNSUPPORTED;
            message = "类型不可用或管控台禁止创建：" + dbType;
        } else if (bound) {
            boolean running = protocolAdapter.isRunning();
            status = running ? InstanceStatus.RUNNING : InstanceStatus.STOPPED;
            message = running
                    ? "已绑定并运行 · " + protocolAdapter.getProtocolName()
                    : "已绑定 · 已停止";
            activeSessions = protocolAdapter.getActiveSessions().size();
            if (protocolAdapter instanceof AbstractProtocolAdapter abstractAdapter) {
                activeConnections = abstractAdapter.getActiveConnectionCount();
                maxConnections = abstractAdapter.getMaxConnections();
            }
            metrics = runtimeMetrics.snapshot();
            startable = true;
        } else {
            status = InstanceStatus.UNBOUND;
            message = "已配置；等待多监听运行时绑定（当前进程仅绑定一个 ProtocolAdapter）";
        }

        return new GatewayInstance(
                id,
                name,
                dbType,
                listenHost,
                listenPort,
                entry.isEnabled(),
                status,
                bound,
                startable,
                targetHost,
                targetPort,
                targetDatabase,
                targetUsername,
                passwordConfigured,
                activeSessions,
                activeConnections,
                maxConnections,
                metrics,
                message
        );
    }

    /**
     * MVP: the process-level adapter is bound to the instance whose dbType and
     * listenPort match {@code gateway.proxy-db-type} / {@code gateway.proxy-port}.
     */
    private boolean isBound(String dbType, int listenPort) {
        String processType = gatewayConfig.getProxyDbType().toLowerCase(Locale.ROOT).trim();
        if ("postgres".equals(processType)) {
            processType = "postgresql";
        }
        String normalized = "postgres".equals(dbType) ? "postgresql" : dbType;
        return normalized.equals(processType) && listenPort == gatewayConfig.getProxyPort();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : (fallback != null ? fallback : "");
    }
}
