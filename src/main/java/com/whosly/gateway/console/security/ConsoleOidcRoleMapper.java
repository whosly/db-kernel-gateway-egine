package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.Oidc;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps IdP OIDC/OAuth2 claims (roles / groups / realm_access.roles) to
 * {@code ROLE_CONSOLE_ADMIN} / {@code ROLE_CONSOLE_VIEWER}.
 * Missing or unmatched roles → VIEWER only.
 */
public class ConsoleOidcRoleMapper {

    public static final String ROLE_ADMIN = "ROLE_" + ConsoleSecurityConfig.ROLE_ADMIN;
    public static final String ROLE_VIEWER = "ROLE_" + ConsoleSecurityConfig.ROLE_VIEWER;

    private final Oidc oidc;

    public ConsoleOidcRoleMapper(Oidc oidc) {
        this.oidc = Objects.requireNonNull(oidc, "oidc");
    }

    public Collection<? extends GrantedAuthority> mapAuthorities(Map<String, Object> claims) {
        Set<String> idpRoles = extractRoleValues(claims, oidc.getRoleClaim());
        boolean admin = matchesAny(idpRoles, oidc.adminRoleValueList());
        List<GrantedAuthority> out = new ArrayList<>(2);
        if (admin) {
            out.add(new SimpleGrantedAuthority(ROLE_ADMIN));
            out.add(new SimpleGrantedAuthority(ROLE_VIEWER));
            return out;
        }
        // viewer match or default
        out.add(new SimpleGrantedAuthority(ROLE_VIEWER));
        return out;
    }

    /**
     * Package-visible for tests. Supports dotted paths into nested maps and Collection/array values.
     */
    static Set<String> extractRoleValues(Map<String, Object> claims, String claimPath) {
        Set<String> out = new LinkedHashSet<>();
        if (claims == null || claims.isEmpty()) {
            return out;
        }
        String path = claimPath != null && !claimPath.isBlank() ? claimPath.trim() : "roles";
        collectStrings(resolvePath(claims, path), out);
        // Fallbacks commonly used by Keycloak / generic IdPs when configured claim is empty
        if (out.isEmpty() && !"realm_access.roles".equals(path)) {
            collectStrings(resolvePath(claims, "realm_access.roles"), out);
        }
        if (out.isEmpty() && !"groups".equals(path)) {
            collectStrings(claims.get("groups"), out);
        }
        if (out.isEmpty() && !"roles".equals(path)) {
            collectStrings(claims.get("roles"), out);
        }
        return out;
    }

    private static Object resolvePath(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(part);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static void collectStrings(Object node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node instanceof Collection<?> col) {
            for (Object o : col) {
                if (o != null) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (node.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(node);
            for (int i = 0; i < len; i++) {
                Object o = java.lang.reflect.Array.get(node, i);
                if (o != null) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (node instanceof String s) {
            for (String part : s.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        } else {
            out.add(String.valueOf(node).trim());
        }
    }

    private static boolean matchesAny(Set<String> idpRoles, List<String> configured) {
        if (idpRoles.isEmpty() || configured == null || configured.isEmpty()) {
            return false;
        }
        for (String idp : idpRoles) {
            String a = idp.toLowerCase(Locale.ROOT);
            for (String cfg : configured) {
                if (a.equals(cfg.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
