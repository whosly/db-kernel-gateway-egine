package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SpEL bean for {@code @PreAuthorize("@consoleAuthz.has('instances:read')")}.
 * {@link AuthMode#OPEN} always grants (lab).
 */
@Component("consoleAuthz")
public class ConsoleAuthz {

    private final AuthMode authMode;

    public ConsoleAuthz(AuthMode authMode) {
        this.authMode = authMode;
    }

    public boolean has(String permission) {
        if (authMode == AuthMode.OPEN) {
            return true;
        }
        if (permission == null || permission.isBlank()) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String principal = String.valueOf(auth.getPrincipal());
        if ("anonymousUser".equals(principal)) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (permission.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAny(String... permissions) {
        if (permissions == null || permissions.length == 0) {
            return false;
        }
        for (String p : permissions) {
            if (has(p)) {
                return true;
            }
        }
        return false;
    }

    public AuthMode mode() {
        return authMode;
    }
}
