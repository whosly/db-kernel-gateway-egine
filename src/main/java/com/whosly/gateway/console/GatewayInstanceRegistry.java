package com.whosly.gateway.console;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.adapter.protocol.SessionSnapshot;
import com.whosly.gateway.console.GatewayInstance.InstanceStatus;
import java.util.Collection;
import com.whosly.gateway.console.masking.InstanceMaskingEngineFactory;
import com.whosly.gateway.console.persist.MaskingRuleRecord;
import com.whosly.gateway.console.persist.MaskingRuleStore;
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

    /** Active session snapshots for an instance (empty when unbound/stopped). */
    public Collection<SessionSnapshot> sessionSnapshots(String id) {
        require(id);
        return listenerRuntime.getAdapter(id.trim())
                .map(ProtocolAdapter::getActiveSessionSnapshots)
                .orElse(List.of());
    }

    /**
     * Kill one client session by connectionId. Returns false when unknown.
     * Closes client leg only (see AbstractProtocolAdapter#killClientSession).
     */
    public boolean killSession(String id, String connectionId) {
        require(id);
        return listenerRuntime.getAdapter(id.trim())
                .map(adapter -> adapter.killClientSession(connectionId))
                .orElse(false);
    }


    public List<MaskingRuleRecord> listMaskingRules(String instanceId) {
        require(instanceId);
        return maskingStore().findByInstanceId(instanceId.trim());
    }

    public MaskingRuleRecord createMaskingRule(String instanceId, MaskingRuleRecord draft) {
        require(instanceId);
        InstanceMaskingEngineFactory factory = maskingFactory();
        MaskingRuleRecord row = withInstance(instanceId.trim(), draft);
        factory.compiler().validateForPersist(row);
        MaskingRuleRecord saved = factory.store().insert(row);
        listenerRuntime.reloadMasking(instanceId.trim());
        return saved;
    }

    public MaskingRuleRecord updateMaskingRule(String instanceId, String ruleId, MaskingRuleRecord draft) {
        require(instanceId);
        InstanceMaskingEngineFactory factory = maskingFactory();
        MaskingRuleRecord existing = factory.store()
                .findByIdAndInstance(ruleId, instanceId.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown masking rule id: " + ruleId));
        MaskingRuleRecord row = new MaskingRuleRecord(
                existing.id(),
                existing.instanceId(),
                draft.name() != null ? draft.name() : existing.name(),
                draft.strategy() != null ? draft.strategy() : existing.strategy(),
                draft.priority(),
                draft.columnName(),
                draft.tableName(),
                draft.namePattern(),
                draft.fixedValue(),
                draft.keepPrefix(),
                draft.keepSuffix(),
                draft.hashHexLength(),
                draft.enabled(),
                existing.createdAt(),
                null);
        factory.compiler().validateForPersist(row);
        MaskingRuleRecord saved = factory.store().update(row);
        listenerRuntime.reloadMasking(instanceId.trim());
        return saved;
    }

    public List<MaskingRuleRecord> replaceMaskingRules(String instanceId, List<MaskingRuleRecord> rules) {
        require(instanceId);
        InstanceMaskingEngineFactory factory = maskingFactory();
        List<MaskingRuleRecord> prepared = new ArrayList<>();
        for (MaskingRuleRecord draft : rules) {
            MaskingRuleRecord row = withInstance(instanceId.trim(), draft);
            factory.compiler().validateForPersist(row);
            prepared.add(row);
        }
        List<MaskingRuleRecord> saved = factory.store().replaceAll(instanceId.trim(), prepared);
        listenerRuntime.reloadMasking(instanceId.trim());
        return saved;
    }

    public Map<String, Object> deleteMaskingRule(String instanceId, String ruleId) {
        require(instanceId);
        InstanceMaskingEngineFactory factory = maskingFactory();
        boolean deleted = factory.store().deleteById(ruleId, instanceId.trim());
        if (!deleted) {
            throw new IllegalArgumentException("Unknown masking rule id: " + ruleId);
        }
        Map<String, Object> reload = listenerRuntime.reloadMasking(instanceId.trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("deleted", true);
        body.put("id", ruleId);
        body.put("instanceId", instanceId.trim());
        body.put("reload", reload);
        return body;
    }

    public Map<String, Object> reloadMasking(String instanceId) {
        require(instanceId);
        return listenerRuntime.reloadMasking(instanceId.trim());
    }

    private InstanceMaskingEngineFactory maskingFactory() {
        return listenerRuntime.maskingEngineFactory().orElseThrow(() ->
                new IllegalStateException("Masking rule store is not configured"));
    }

    private MaskingRuleStore maskingStore() {
        return maskingFactory().store();
    }

    private static MaskingRuleRecord withInstance(String instanceId, MaskingRuleRecord draft) {
        Objects.requireNonNull(draft, "draft");
        return new MaskingRuleRecord(
                draft.id(),
                instanceId,
                draft.name(),
                draft.strategy(),
                draft.priority(),
                draft.columnName(),
                draft.tableName(),
                draft.namePattern(),
                draft.fixedValue(),
                draft.keepPrefix(),
                draft.keepSuffix(),
                draft.hashHexLength(),
                draft.enabled(),
                draft.createdAt(),
                draft.updatedAt());
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
