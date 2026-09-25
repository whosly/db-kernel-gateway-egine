package com.whosly.gateway.console.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds Spring authorities: {@code ROLE_CONSOLE_*} plus permission strings.
 */
public final class ConsoleAuthoritySupport {

    private ConsoleAuthoritySupport() {
    }

    public static Collection<GrantedAuthority> authoritiesForRoles(String... roleNames) {
        Set<String> roles = new LinkedHashSet<>();
        if (roleNames != null) {
            for (String r : roleNames) {
                if (r != null && !r.isBlank()) {
                    roles.add(ConsoleRoles.normalize(r));
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add(ConsoleRoles.VIEWER);
        }
        // ADMIN implies highest; keep declared roles for /auth/me
        Set<GrantedAuthority> out = new LinkedHashSet<>();
        for (String role : roles) {
            out.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        for (String perm : ConsoleRoles.permissionsForRoles(roles)) {
            out.add(new SimpleGrantedAuthority(perm));
        }
        return List.copyOf(out);
    }

    public static List<String> roleAuthorities(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) {
            return List.of();
        }
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static List<String> permissionAuthorities(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) {
            return List.of();
        }
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.contains(":") && !a.startsWith("ROLE_"))
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static List<String> allPermissionsSorted() {
        return ConsolePermission.ALL.stream().sorted().collect(Collectors.toCollection(ArrayList::new));
    }
}
