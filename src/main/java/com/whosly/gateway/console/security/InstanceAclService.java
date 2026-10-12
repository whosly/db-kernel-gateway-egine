package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.InstanceAclStore;
import com.whosly.gateway.console.persist.InstanceAclStore.AclWriteItem;
import com.whosly.gateway.console.persist.InstanceAclStore.InstanceAclRecord;
import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Instance-scoped ACL evaluation and binding management. See CONSOLE_ARCHITECTURE §24.
 */
@Service
public class InstanceAclService {

    private final InstanceAclStore store;
    private final AuthMode authMode;

    public InstanceAclService(InstanceAclStore store, AuthMode authMode) {
        this.store = Objects.requireNonNull(store, "store");
        this.authMode = Objects.requireNonNull(authMode, "authMode");
    }

    public boolean isAclEnforcementActive() {
        return authMode != AuthMode.OPEN;
    }

    /**
     * Whether the current principal is restricted by ACL bindings (has at least one matching row).
     * ADMIN and open mode are never restricted.
     */
    public boolean isCurrentUserRestricted() {
        if (!isAclEnforcementActive() || isCurrentUserAdmin()) {
            return false;
        }
        PrincipalRef ref = currentPrincipal();
        if (ref == null) {
            return false;
        }
        return store.hasAnyMatching(ref.username(), ref.roles());
    }

    public boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            String authz = a.getAuthority();
            if (("ROLE_" + ConsoleRoles.ADMIN).equals(authz) || ConsoleRoles.ADMIN.equals(authz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Effective instance-scoped permissions for current user on one instance (intersection already
     * applied against global authorities when {@code globalPerms} provided).
     * Empty set means no access when restricted; unrestricted callers should not use this alone.
     */
    public Set<String> effectiveInstancePermissions(String instanceId, Set<String> globalPerms) {
        Set<String> global = globalPerms != null ? globalPerms : Set.of();
        if (!isAclEnforcementActive() || isCurrentUserAdmin()) {
            return global.stream().filter(ConsolePermission::isInstanceScoped).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        PrincipalRef ref = currentPrincipal();
        if (ref == null) {
            return Set.of();
        }
        List<InstanceAclRecord> matching = store.findMatching(ref.username(), ref.roles());
        if (matching.isEmpty()) {
            // Unrestricted: role-only
            return global.stream().filter(ConsolePermission::isInstanceScoped).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        Set<String> granted = new LinkedHashSet<>();
        for (InstanceAclRecord row : matching) {
            if (instanceId != null && instanceId.equals(row.instanceId())) {
                granted.addAll(ConsolePermission.parseInstanceScopedCsv(row.permissions()));
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (String p : granted) {
            if (global.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * @return true if current user may use {@code permission} on {@code instanceId}
     *         (caller must already have verified global permission, or pass checkGlobal=true).
     */
    public boolean canAccessInstance(String instanceId, String permission, boolean hasGlobalPermission) {
        if (!isAclEnforcementActive()) {
            return true;
        }
        if (!hasGlobalPermission) {
            return false;
        }
        if (isCurrentUserAdmin()) {
            return true;
        }
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        if (!ConsolePermission.isInstanceScoped(permission)) {
            // Non-instance perms are not gated by ACL
            return hasGlobalPermission;
        }
        PrincipalRef ref = currentPrincipal();
        if (ref == null) {
            return false;
        }
        List<InstanceAclRecord> matching = store.findMatching(ref.username(), ref.roles());
        if (matching.isEmpty()) {
            return true; // unrestricted
        }
        Set<String> granted = new LinkedHashSet<>();
        for (InstanceAclRecord row : matching) {
            if (instanceId.equals(row.instanceId())) {
                granted.addAll(ConsolePermission.parseInstanceScopedCsv(row.permissions()));
            }
        }
        return granted.contains(permission);
    }

    /** Instance ids the current user may see (needs instances:read effectively). */
    public Set<String> visibleInstanceIds(Collection<String> allInstanceIds, boolean hasInstancesRead) {
        if (!isAclEnforcementActive() || isCurrentUserAdmin() || !hasInstancesRead) {
            if (!hasInstancesRead) {
                return Set.of();
            }
            return allInstanceIds == null ? Set.of() : new LinkedHashSet<>(allInstanceIds);
        }
        PrincipalRef ref = currentPrincipal();
        if (ref == null) {
            return Set.of();
        }
        List<InstanceAclRecord> matching = store.findMatching(ref.username(), ref.roles());
        if (matching.isEmpty()) {
            return allInstanceIds == null ? Set.of() : new LinkedHashSet<>(allInstanceIds);
        }
        Set<String> out = new LinkedHashSet<>();
        for (InstanceAclRecord row : matching) {
            Set<String> perms = ConsolePermission.parseInstanceScopedCsv(row.permissions());
            if (perms.contains(ConsolePermission.INSTANCES_READ) && allInstanceIds != null
                    && allInstanceIds.contains(row.instanceId())) {
                out.add(row.instanceId());
            }
        }
        return out;
    }

    /** Map instanceId → effective instance-scoped perms for /auth/me (when restricted). */
    public Map<String, List<String>> instanceAccessSummary(Set<String> globalPerms) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!isAclEnforcementActive() || isCurrentUserAdmin()) {
            return out;
        }
        PrincipalRef ref = currentPrincipal();
        if (ref == null) {
            return out;
        }
        List<InstanceAclRecord> matching = store.findMatching(ref.username(), ref.roles());
        if (matching.isEmpty()) {
            return out;
        }
        Map<String, Set<String>> byInst = new LinkedHashMap<>();
        for (InstanceAclRecord row : matching) {
            byInst.computeIfAbsent(row.instanceId(), k -> new LinkedHashSet<>())
                    .addAll(ConsolePermission.parseInstanceScopedCsv(row.permissions()));
        }
        Set<String> global = globalPerms != null ? globalPerms : Set.of();
        for (Map.Entry<String, Set<String>> e : byInst.entrySet()) {
            List<String> inter = e.getValue().stream()
                    .filter(global::contains)
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!inter.isEmpty()) {
                out.put(e.getKey(), inter);
            }
        }
        return out;
    }

    public List<InstanceAclRecord> listByInstance(String instanceId) {
        return store.findByInstanceId(instanceId);
    }

    public List<InstanceAclRecord> listAll() {
        return store.findAll();
    }

    public InstanceAclRecord upsertBinding(String instanceId, String principalType,
                                           String principal, List<String> permissions) {
        Set<String> cleaned = sanitizePermissions(permissions);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("permissions 不能为空（须为实例域权限）");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        return store.upsert(principalType, principal, instanceId, ConsolePermission.toCsv(cleaned));
    }

    public List<InstanceAclRecord> replaceBindings(String instanceId, List<BindingInput> items) {
        List<AclWriteItem> writes = new ArrayList<>();
        if (items != null) {
            for (BindingInput item : items) {
                if (item == null) {
                    continue;
                }
                Set<String> cleaned = sanitizePermissions(item.permissions());
                if (cleaned.isEmpty()) {
                    throw new IllegalArgumentException(
                            "绑定 " + item.principalType() + "/" + item.principal() + " 的 permissions 无效");
                }
                writes.add(new AclWriteItem(
                        item.principalType(), item.principal(), ConsolePermission.toCsv(cleaned)));
            }
        }
        return store.replaceForInstance(instanceId, writes);
    }

    public boolean deleteBinding(String bindingId) {
        return store.delete(bindingId);
    }

    public void deleteBindingsForInstance(String instanceId) {
        store.deleteByInstanceId(instanceId);
    }

    private static Set<String> sanitizePermissions(List<String> permissions) {
        Set<String> out = new LinkedHashSet<>();
        if (permissions == null) {
            return out;
        }
        for (String p : permissions) {
            if (p != null && ConsolePermission.isInstanceScoped(p.trim().toLowerCase())) {
                out.add(p.trim().toLowerCase());
            }
        }
        return out;
    }

    private PrincipalRef currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || "anonymousUser".equals(name)) {
            return null;
        }
        List<String> roles = new ArrayList<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            String authz = a.getAuthority();
            if (authz != null && authz.startsWith("ROLE_")) {
                roles.add(ConsoleRoles.normalize(authz.substring(5)));
            }
        }
        return new PrincipalRef(name, roles);
    }

    public record PrincipalRef(String username, List<String> roles) {
    }

    public record BindingInput(String principalType, String principal, List<String> permissions) {
    }
}
