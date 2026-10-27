package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.InstanceAclStore;
import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static com.whosly.gateway.console.security.ConsolePermission.*;
import static org.assertj.core.api.Assertions.assertThat;

class InstanceAclServiceTest {

    private InstanceAclService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:acl-svc-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        service = new InstanceAclService(new InstanceAclStore(new JdbcTemplate(ds)), AuthMode.FORM);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        user, "N/A",
                        ConsoleAuthoritySupport.authoritiesForRoles(role)));
    }

    @Test
    void openModeWouldBeInactive_butServiceUsesForm() {
        // unrestricted when no bindings
        as("alice", ConsoleRoles.OPERATOR);
        assertThat(service.isCurrentUserRestricted()).isFalse();
        assertThat(service.canAccessInstance("gw-1", SQL_EXECUTE, true)).isTrue();
        assertThat(service.visibleInstanceIds(List.of("gw-1", "gw-2"), true))
                .containsExactlyInAnyOrder("gw-1", "gw-2");
    }

    @Test
    void userBindingRestrictsInstancesAndIntersectsPerms() {
        as("alice", ConsoleRoles.OPERATOR);
        service.upsertBinding("gw-1", "user", "alice", List.of(INSTANCES_READ, SQL_EXECUTE));
        // OPERATOR has start_stop globally but ACL does not grant it on gw-1
        assertThat(service.isCurrentUserRestricted()).isTrue();
        assertThat(service.canAccessInstance("gw-1", SQL_EXECUTE, true)).isTrue();
        assertThat(service.canAccessInstance("gw-1", INSTANCES_START_STOP, true)).isFalse();
        assertThat(service.canAccessInstance("gw-2", INSTANCES_READ, true)).isFalse();
        assertThat(service.visibleInstanceIds(List.of("gw-1", "gw-2"), true))
                .containsExactly("gw-1");
    }

    @Test
    void roleBindingApplies() {
        as("bob", ConsoleRoles.VIEWER);
        service.upsertBinding("gw-9", "role", "VIEWER", List.of(INSTANCES_READ, SCHEMA_READ));
        assertThat(service.canAccessInstance("gw-9", INSTANCES_READ, true)).isTrue();
        assertThat(service.canAccessInstance("gw-9", SQL_EXECUTE, true)).isFalse(); // not in global either, but also not in ACL
        assertThat(service.canAccessInstance("gw-8", INSTANCES_READ, true)).isFalse();
    }

    @Test
    void adminBypassesAcl() {
        as("root", ConsoleRoles.ADMIN);
        service.upsertBinding("gw-1", "user", "other", List.of(INSTANCES_READ));
        // admin has no personal binding but other users do — admin still unrestricted
        assertThat(service.isCurrentUserRestricted()).isFalse();
        assertThat(service.canAccessInstance("gw-1", INSTANCES_DELETE, true)).isTrue();
        assertThat(service.canAccessInstance("gw-99", SQL_EXECUTE, true)).isTrue();
    }

    @Test
    void aclInactiveInOpenMode() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:acl-open-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        InstanceAclService open = new InstanceAclService(new InstanceAclStore(new JdbcTemplate(ds)), AuthMode.OPEN);
        as("alice", ConsoleRoles.VIEWER);
        open.upsertBinding("gw-1", "user", "alice", List.of(INSTANCES_READ));
        assertThat(open.isAclEnforcementActive()).isFalse();
        assertThat(open.canAccessInstance("gw-2", INSTANCES_READ, true)).isTrue();
    }

    @Test
    void instanceAccessSummary() {
        as("alice", ConsoleRoles.OPERATOR);
        service.upsertBinding("gw-1", "user", "alice", List.of(INSTANCES_READ, SQL_EXECUTE, SECURITY_KEYS));
        // SECURITY_KEYS stripped at upsert
        var summary = service.instanceAccessSummary(Set.copyOf(ConsoleRoles.permissionsOf(ConsoleRoles.OPERATOR)));
        assertThat(summary).containsKey("gw-1");
        assertThat(summary.get("gw-1")).contains(INSTANCES_READ, SQL_EXECUTE);
        assertThat(summary.get("gw-1")).doesNotContain(SECURITY_KEYS);
    }
}
