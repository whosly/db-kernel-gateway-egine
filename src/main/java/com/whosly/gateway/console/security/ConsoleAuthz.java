package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SpEL bean for {@code @PreAuthorize("@consoleAuthz.has('instances:read')")}
 * and {@code @consoleAuthz.canAccessInstance(#id, 'sql:execute')}.
 * {@link AuthMode#OPEN} always grants (lab). Instance ACL: CONSOLE_ARCHITECTURE §24.
 */
@Component("consoleAuthz")
public class ConsoleAuthz {

    private final AuthMode authMode;
    private final ObjectProvider<InstanceAclService> aclService;

    public ConsoleAuthz(AuthMode authMode, ObjectProvider<InstanceAclService> aclService) {
        this.authMode = authMode;
        this.aclService = aclService;
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

    /**
     * Global permission AND instance ACL (§24). OPEN → true; ADMIN bypasses ACL.
     */
    public boolean canAccessInstance(String instanceId, String permission) {
        if (authMode == AuthMode.OPEN) {
            return true;
        }
        if (!has(permission)) {
            return false;
        }
        InstanceAclService acl = aclService.getIfAvailable();
        if (acl == null) {
            return true;
        }
        return acl.canAccessInstance(instanceId, permission, true);
    }

    public AuthMode mode() {
        return authMode;
    }
}
