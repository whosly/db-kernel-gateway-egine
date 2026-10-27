package com.whosly.gateway.console.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Console RBAC permission catalog (stable string constants).
 * See CONSOLE_ARCHITECTURE §22 / §24.
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
    /** Manage instance ACL bindings (ADMIN only). */
    public static final String ACL_MANAGE = "acl:manage";

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
            CONFIG_READ,
            ACL_MANAGE
    )));

    /**
     * Permissions that may appear in instance ACL CSV (§24).
     * Global-only perms (security:keys, audit, alerts, risk, config, acl:manage) are excluded.
     */
    public static final Set<String> INSTANCE_SCOPED = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            INSTANCES_READ,
            INSTANCES_WRITE,
            INSTANCES_START_STOP,
            INSTANCES_DELETE,
            SQL_EXECUTE,
            SESSIONS_KILL,
            MASKING_WRITE,
            SCHEMA_READ,
            METRICS_READ
    )));

    private ConsolePermission() {
    }

    public static boolean isInstanceScoped(String permission) {
        return permission != null && INSTANCE_SCOPED.contains(permission.trim());
    }

    /** Parse CSV, keep only known instance-scoped permissions, stable order. */
    public static Set<String> parseInstanceScopedCsv(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (INSTANCE_SCOPED.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    public static String toCsv(Set<String> perms) {
        if (perms == null || perms.isEmpty()) {
            return "";
        }
        return perms.stream()
                .filter(INSTANCE_SCOPED::contains)
                .sorted()
                .collect(Collectors.joining(","));
    }
}
