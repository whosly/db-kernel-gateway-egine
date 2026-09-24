package com.whosly.gateway.controller.console;

import com.whosly.gateway.adapter.AbstractProtocolAdapter;
import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.GatewayRuntimeMetrics;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.GatewayInstance;
import com.whosly.gateway.console.GatewayInstanceRegistry;
import com.whosly.gateway.console.SupportedDatabaseCatalog;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CreateInstanceRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
@RestController
@RequestMapping("/console/api")
public class ConsoleApiController {

    private final SupportedDatabaseCatalog catalog;
    private final GatewayInstanceRegistry instanceRegistry;
    private final ProtocolAdapter protocolAdapter;
    private final GatewayRuntimeMetrics runtimeMetrics;
    private final GatewayConfig gatewayConfig;

    @Autowired
    public ConsoleApiController(SupportedDatabaseCatalog catalog,
                                GatewayInstanceRegistry instanceRegistry,
                                ProtocolAdapter protocolAdapter,
                                GatewayRuntimeMetrics runtimeMetrics,
                                GatewayConfig gatewayConfig) {
        this.catalog = catalog;
        this.instanceRegistry = instanceRegistry;
        this.protocolAdapter = protocolAdapter;
        this.runtimeMetrics = runtimeMetrics != null ? runtimeMetrics : GatewayRuntimeMetrics.noop();
        this.gatewayConfig = gatewayConfig;
    }

    @GetMapping("/supported-databases")
    public Map<String, Object> supportedDatabases() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("databases", catalog.listAll());
        body.put("note", "类型目录（可插拔适配器注册表），与实例注册表分离");
        return body;
    }

    @GetMapping("/instances")
    public Map<String, Object> listInstances() {
        List<GatewayInstance> instances = instanceRegistry.listInstances();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instances", instances);
        body.put("count", instances.size());
        body.put("byStatus", instances.stream()
                .collect(Collectors.groupingBy(i -> i.status().name(), Collectors.counting())));
        return body;
    }

    @GetMapping("/instances/{id}")
    public GatewayInstance getInstance(@PathVariable("id") String id) {
        return instanceRegistry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway instance id: " + id));
    }

    @GetMapping("/instances/{id}/status")
    public Map<String, Object> instanceStatus(@PathVariable("id") String id) {
        return instanceRegistry.statusOf(id);
    }

    @GetMapping("/instances/{id}/metrics")
    public Map<String, Object> instanceMetrics(@PathVariable("id") String id) {
        return instanceRegistry.metricsOf(id);
    }

    @PostMapping("/instances/{id}/start")
    public Map<String, Object> startInstance(@PathVariable("id") String id) {
        return instanceRegistry.start(id);
    }

    @PostMapping("/instances/{id}/stop")
    public Map<String, Object> stopInstance(@PathVariable("id") String id) {
        return instanceRegistry.stop(id);
    }

    /**
     * Create a console-managed instance: persist to H2, bind ProtocolAdapter, optionally auto-start.
     * Password is accepted but never echoed.
     */
    @PostMapping("/instances")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayInstance createInstance(@RequestBody CreateInstanceBody body) {
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
        return instanceRegistry.create(request);
    }

    @DeleteMapping("/instances/{id}")
    public Map<String, Object> deleteInstance(@PathVariable("id") String id) {
        Map<String, Object> result = instanceRegistry.remove(id);
        if (Boolean.FALSE.equals(result.get("ok"))) {
            throw new IllegalArgumentException(String.valueOf(result.get("message")));
        }
        return result;
    }

    @GetMapping("/health")
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

    @GetMapping("/config/summary")
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
        return body;
    }

    @GetMapping("/overview")
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

    /** Sum per-instance metric maps for control-plane KPIs. */
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

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", ex.getMessage());
        return body;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    /**
     * JSON body for POST /instances. Password accepted, never returned on GatewayInstance.
     */
    public record CreateInstanceBody(
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
}
