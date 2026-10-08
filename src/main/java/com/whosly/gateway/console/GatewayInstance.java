package com.whosly.gateway.console;

import java.util.Map;
import java.util.Objects;

/**
 * First-class console entity: one gateway listener slot (protocol-agnostic).
 * {@code dbType} is a catalog attribute, not a UI/layout branch key.
 * {@code proxyMode} / {@code proxyModeLabel} are derived labeling (网关代理 vs 透明代理).
 */
public record GatewayInstance(
        String id,
        String name,
        String dbType,
        String listenHost,
        int listenPort,
        boolean enabled,
        InstanceStatus status,
        boolean bound,
        boolean startable,
        String targetHost,
        int targetPort,
        String targetDatabase,
        String targetUsername,
        boolean passwordConfigured,
        Integer activeSessions,
        Integer activeConnections,
        Integer maxConnections,
        Map<String, Long> metrics,
        String message,
        String source,
        ProxyCapability proxyMode,
        String proxyModeLabel
) {
    public enum InstanceStatus {
        RUNNING,
        STOPPED,
        DISABLED,
        UNBOUND,
        UNSUPPORTED
    }

    public GatewayInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(listenHost, "listenHost");
        Objects.requireNonNull(status, "status");
        metrics = metrics != null ? Map.copyOf(metrics) : Map.of();
        message = message != null ? message : "";
        source = (source != null && !source.isBlank()) ? source : "config";
        ProxyCapability resolved = proxyMode != null ? proxyMode : ProxyCapability.fromDbType(dbType);
        proxyMode = resolved;
        proxyModeLabel = (proxyModeLabel != null && !proxyModeLabel.isBlank())
                ? proxyModeLabel
                : resolved.label();
    }
}
