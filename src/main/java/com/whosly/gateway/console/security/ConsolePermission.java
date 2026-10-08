package com.whosly.gateway.console.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Console RBAC permission catalog (stable string constants).
 * See CONSOLE_ARCHITECTURE §22.
 */
public final class ConsolePermission {

    public static final String INSTANCES_READ = "instances:read";
    public static final String INSTANCES_WRITE = "instances:write";
    public static final String INSTANCES_START_STOP = "instances:start_stop";
    public static final String INSTANCES_DELETE = "instances:delete";
    public static final String SQL_EXECUTE = "sql:execute";
    public static final String SESSIONS_KILL = "sessions:kill";
    public static final String MASKING_WRITE = "masking:write";
    public static final String SECURITY_KEYS = "security:keys";
    public static final String AUDIT_READ = "audit:read";
    public static final String ALERTS_READ = "alerts:read";
    public static final String ALERTS_WRITE = "alerts:write";
    public static final String RISK_WRITE = "risk:write";
    public static final String METRICS_READ = "metrics:read";
    public static final String SCHEMA_READ = "schema:read";
    public static final String CONFIG_READ = "config:read";

    /** All catalog permissions in stable order. */
    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            INSTANCES_READ,
            INSTANCES_WRITE,
            INSTANCES_START_STOP,
            INSTANCES_DELETE,
            SQL_EXECUTE,
            SESSIONS_KILL,
            MASKING_WRITE,
            SECURITY_KEYS,
            AUDIT_READ,
            ALERTS_READ,
            ALERTS_WRITE,
            RISK_WRITE,
            METRICS_READ,
            SCHEMA_READ,
            CONFIG_READ
    )));

    private ConsolePermission() {
    }
}
