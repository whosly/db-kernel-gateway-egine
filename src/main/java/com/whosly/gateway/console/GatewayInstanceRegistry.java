package com.whosly.gateway.console;

import com.whosly.gateway.console.GatewayInstance.InstanceStatus;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.CreateInstanceRequest;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protocol-agnostic multi-instance registry for the console.
 *
 * <p>Delegates listener lifecycle to {@link GatewayListenerRuntime}. Every
 * configured creatable+enabled instance is <em>bound</em> to its own
 * {@code ProtocolAdapter}; disabled / unsupported slots never get an adapter.</p>
 */
@Service
public class GatewayInstanceRegistry {

    private final GatewayListenerRuntime listenerRuntime;

    public GatewayInstanceRegistry(GatewayListenerRuntime listenerRuntime) {
        this.listenerRuntime = Objects.requireNonNull(listenerRuntime, "listenerRuntime");
    }

    public List<GatewayInstance> listInstances() {
        List<GatewayInstance> out = new ArrayList<>();
        for (ManagedListener listener : listenerRuntime.list()) {
            out.add(toInstance(listener));
        }
        return List.copyOf(out);
    }

    public Optional<GatewayInstance> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return listenerRuntime.find(id.trim()).map(this::toInstance);
    }

    public Map<String, Object> start(String id) {
        return listenerRuntime.start(id);
    }

    public Map<String, Object> stop(String id) {
        return listenerRuntime.stop(id);
    }

    public GatewayInstance create(CreateInstanceRequest request) {
        return toInstance(listenerRuntime.addInstance(request));
    }

    public Map<String, Object> remove(String id) {
        return listenerRuntime.removeInstance(id);
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

    private GatewayInstance toInstance(ManagedListener listener) {
        InstanceStatus status;
        String message;
        boolean startable = false;
        Integer activeSessions = null;
        Integer activeConnections = null;
        Integer maxConnections = null;
        Map<String, Long> metrics = Map.of();

        if (!listener.enabled()) {
            status = InstanceStatus.DISABLED;
            message = "配置未启用";
        } else if (!listener.creatable() || listener.adapter() == null) {
            status = InstanceStatus.UNSUPPORTED;
            message = "类型不可用或不可创建：" + listener.dbType();
        } else if (listener.isRunning()) {
            status = InstanceStatus.RUNNING;
            message = "已绑定并运行 · " + listener.adapter().getProtocolName();
            activeSessions = listener.activeSessions();
            activeConnections = listener.activeConnections();
            maxConnections = listener.maxConnections();
            metrics = listener.metrics().snapshot();
            startable = true;
        } else {
            status = InstanceStatus.STOPPED;
            message = "已绑定 · 已停止";
            activeSessions = listener.activeSessions();
            activeConnections = listener.activeConnections();
            maxConnections = listener.maxConnections();
            metrics = listener.metrics().snapshot();
            startable = true;
        }

        return new GatewayInstance(
                listener.id(),
                listener.name(),
                listener.dbType(),
                listener.listenHost(),
                listener.listenPort(),
                listener.enabled(),
                status,
                listener.bound(),
                startable,
                listener.targetHost(),
                listener.targetPort(),
                listener.targetDatabase(),
                listener.targetUsername(),
                listener.passwordConfigured(),
                activeSessions,
                activeConnections,
                maxConnections,
                metrics,
                message,
                listener.source()
        );
    }
}
