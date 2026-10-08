package com.whosly.gateway.console.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Maps console roles to permission sets. Role names without {@code ROLE_} prefix:
 * {@code CONSOLE_VIEWER} / {@code CONSOLE_OPERATOR} / {@code CONSOLE_ADMIN}.
 */
public final class ConsoleRoles {

    public static final String VIEWER = ConsoleSecurityConfig.ROLE_VIEWER;
    public static final String OPERATOR = ConsoleSecurityConfig.ROLE_OPERATOR;
    public static final String ADMIN = ConsoleSecurityConfig.ROLE_ADMIN;

    private static final Set<String> VIEWER_PERMS;
    private static final Set<String> OPERATOR_PERMS;
    private static final Set<String> ADMIN_PERMS;

    static {
        Set<String> viewer = new LinkedHashSet<>();
        viewer.add(ConsolePermission.INSTANCES_READ);
        viewer.add(ConsolePermission.AUDIT_READ);
        viewer.add(ConsolePermission.ALERTS_READ);
        viewer.add(ConsolePermission.METRICS_READ);
        viewer.add(ConsolePermission.SCHEMA_READ);
        viewer.add(ConsolePermission.CONFIG_READ);
        VIEWER_PERMS = Collections.unmodifiableSet(viewer);

        Set<String> operator = new LinkedHashSet<>(VIEWER_PERMS);
        operator.add(ConsolePermission.INSTANCES_START_STOP);
        operator.add(ConsolePermission.SQL_EXECUTE);
        operator.add(ConsolePermission.SESSIONS_KILL);
        operator.add(ConsolePermission.ALERTS_WRITE);
        operator.add(ConsolePermission.MASKING_WRITE);
        OPERATOR_PERMS = Collections.unmodifiableSet(operator);

        ADMIN_PERMS = Collections.unmodifiableSet(new LinkedHashSet<>(ConsolePermission.ALL));
    }

    private ConsoleRoles() {
    }

    public static Set<String> permissionsOf(String roleName) {
        String n = normalize(roleName);
        if (ADMIN.equals(n)) {
            return ADMIN_PERMS;
        }
        if (OPERATOR.equals(n)) {
            return OPERATOR_PERMS;
        }
        if (VIEWER.equals(n)) {
            return VIEWER_PERMS;
        }
        return VIEWER_PERMS;
    }

    /** Union of permissions for one or more role names. */
    public static Set<String> permissionsForRoles(Iterable<String> roleNames) {
        Set<String> out = new LinkedHashSet<>();
        if (roleNames == null) {
            return out;
        }
        for (String r : roleNames) {
            out.addAll(permissionsOf(r));
        }
        return out;
    }

    /**
     * Normalize aliases to {@code CONSOLE_*} role names (no {@code ROLE_} prefix).
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return VIEWER;
        }
        String r = raw.trim();
        if (r.regionMatches(true, 0, "ROLE_", 0, 5)) {
            r = r.substring(5);
        }
        String upper = r.toUpperCase(Locale.ROOT);
        if ("ADMIN".equals(upper) || "CONSOLE_ADMIN".equals(upper)) {
            return ADMIN;
        }
        if ("OPERATOR".equals(upper) || "CONSOLE_OPERATOR".equals(upper) || "OPS".equals(upper)) {
            return OPERATOR;
        }
        if ("VIEWER".equals(upper) || "CONSOLE_VIEWER".equals(upper) || "READ".equals(upper)) {
            return VIEWER;
        }
        return VIEWER;
    }
}
