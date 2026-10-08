package com.whosly.gateway.console;

import java.util.Locale;
import java.util.Objects;

/**
 * Proxy capability mode derived from {@code dbType} (protocol-agnostic labeling).
 * Console badges and catalog create-form use {@link #label()} / {@link #description()}.
 */
public enum ProxyCapability {
    GATEWAY("网关代理", "协议网关（观测/脱敏等）"),
    TRANSPARENT("透明代理", "字节透明转发"),
    UNSUPPORTED("不支持", "该类型尚无可用代理能力");

    private final String label;
    private final String description;

    ProxyCapability(String label, String description) {
        this.label = Objects.requireNonNull(label, "label");
        this.description = Objects.requireNonNull(description, "description");
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * Maps catalog / instance {@code dbType} ids to proxy mode.
     * mysql/mariadb/postgresql/postgres → GATEWAY;
     * sqlserver/mssql → TRANSPARENT;
     * oracle and unknown → UNSUPPORTED.
     */
    public static ProxyCapability fromDbType(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            return UNSUPPORTED;
        }
        String key = dbType.toLowerCase(Locale.ROOT).trim();
        return switch (key) {
            case "mysql", "mariadb", "postgresql", "postgres" -> GATEWAY;
            case "sqlserver", "mssql" -> TRANSPARENT;
            case "oracle" -> UNSUPPORTED;
            default -> UNSUPPORTED;
        };
    }
}
