package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.Oidc;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.whosly.gateway.console.security.ConsolePermission.INSTANCES_START_STOP;
import static com.whosly.gateway.console.security.ConsolePermission.SECURITY_KEYS;
import static org.assertj.core.api.Assertions.assertThat;

class ConsoleOidcRoleMapperTest {

    private ConsoleOidcRoleMapper mapperWithDefaults() {
        return new ConsoleOidcRoleMapper(new Oidc());
    }

    @Test
    void missingRolesDefaultsToViewer() {
        Collection<? extends GrantedAuthority> auths = mapperWithDefaults().mapAuthorities(Map.of("sub", "u1"));
        assertThat(names(auths))
                .contains(ConsoleOidcRoleMapper.ROLE_VIEWER, "instances:read")
                .doesNotContain(ConsoleOidcRoleMapper.ROLE_ADMIN, SECURITY_KEYS);
    }

    @Test
    void mapsRolesClaimToAdmin() {
        Collection<? extends GrantedAuthority> auths = mapperWithDefaults()
                .mapAuthorities(Map.of("roles", List.of("CONSOLE_ADMIN")));
        assertThat(names(auths)).contains(
                ConsoleOidcRoleMapper.ROLE_ADMIN, SECURITY_KEYS, "instances:delete");
    }

    @Test
    void mapsOperatorRole() {
        Collection<? extends GrantedAuthority> auths = mapperWithDefaults()
                .mapAuthorities(Map.of("roles", List.of("CONSOLE_OPERATOR")));
        assertThat(names(auths))
                .contains(ConsoleOidcRoleMapper.ROLE_OPERATOR, INSTANCES_START_STOP)
                .doesNotContain(SECURITY_KEYS, ConsoleOidcRoleMapper.ROLE_ADMIN);
    }

    @Test
    void mapsRealmAccessRolesViaFallback() {
        Oidc oidc = new Oidc();
        oidc.setRoleClaim("roles"); // primary empty → fallback realm_access.roles
        ConsoleOidcRoleMapper mapper = new ConsoleOidcRoleMapper(oidc);
        Map<String, Object> claims = Map.of(
                "realm_access", Map.of("roles", List.of("admin", "offline_access")));
        assertThat(names(mapper.mapAuthorities(claims)))
                .contains(ConsoleOidcRoleMapper.ROLE_ADMIN);
    }

    @Test
    void mapsConfiguredDottedClaim() {
        Oidc oidc = new Oidc();
        oidc.setRoleClaim("realm_access.roles");
        oidc.setAdminRoleValues("console-admin");
        ConsoleOidcRoleMapper mapper = new ConsoleOidcRoleMapper(oidc);
        Map<String, Object> claims = Map.of(
                "realm_access", Map.of("roles", List.of("console-admin")));
        assertThat(names(mapper.mapAuthorities(claims)))
                .contains(ConsoleOidcRoleMapper.ROLE_ADMIN);
    }

    @Test
    void mapsGroupsClaimFallback() {
        Oidc oidc = new Oidc();
        oidc.setRoleClaim("roles");
        ConsoleOidcRoleMapper mapper = new ConsoleOidcRoleMapper(oidc);
        assertThat(names(mapper.mapAuthorities(Map.of("groups", List.of("viewer")))))
                .contains(ConsoleOidcRoleMapper.ROLE_VIEWER)
                .doesNotContain(ConsoleOidcRoleMapper.ROLE_ADMIN);
    }

    @Test
    void mapsLiteralDottedClaimKeyFromKeycloakMapper() {
        Oidc oidc = new Oidc();
        oidc.setRoleClaim("realm_access.roles");
        ConsoleOidcRoleMapper mapper = new ConsoleOidcRoleMapper(oidc);
        Map<String, Object> claims = Map.of("realm_access.roles", List.of("CONSOLE_ADMIN"));
        assertThat(names(mapper.mapAuthorities(claims)))
                .contains(ConsoleOidcRoleMapper.ROLE_ADMIN);
    }

    @Test
    void extractRoleValuesSupportsArray() {
        Set<String> roles = ConsoleOidcRoleMapper.extractRoleValues(
                Map.of("roles", new String[]{"a", "b"}), "roles");
        assertThat(roles).containsExactlyInAnyOrder("a", "b");
    }

    private static Set<String> names(Collection<? extends GrantedAuthority> auths) {
        return auths.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
