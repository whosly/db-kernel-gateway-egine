package com.whosly.gateway.console.security;

import org.junit.jupiter.api.Test;

import static com.whosly.gateway.console.security.ConsolePermission.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConsoleRolesTest {

    @Test
    void viewerIsReadOnly() {
        assertThat(ConsoleRoles.permissionsOf(ConsoleRoles.VIEWER))
                .containsExactlyInAnyOrder(
                        INSTANCES_READ, AUDIT_READ, ALERTS_READ, METRICS_READ, SCHEMA_READ, CONFIG_READ)
                .doesNotContain(INSTANCES_WRITE, INSTANCES_START_STOP, INSTANCES_DELETE,
                        SQL_EXECUTE, SESSIONS_KILL, MASKING_WRITE, SECURITY_KEYS, ALERTS_WRITE, RISK_WRITE);
    }

    @Test
    void operatorCanStartSqlKillAlertsMaskingButNotKeysOrDelete() {
        assertThat(ConsoleRoles.permissionsOf(ConsoleRoles.OPERATOR))
                .contains(INSTANCES_READ, INSTANCES_START_STOP, SQL_EXECUTE, SESSIONS_KILL,
                        ALERTS_WRITE, MASKING_WRITE)
                .doesNotContain(SECURITY_KEYS, INSTANCES_DELETE, INSTANCES_WRITE, RISK_WRITE);
    }

    @Test
    void adminHasFullCatalog() {
        assertThat(ConsoleRoles.permissionsOf(ConsoleRoles.ADMIN))
                .containsAll(ConsolePermission.ALL)
                .contains(SECURITY_KEYS, INSTANCES_DELETE, RISK_WRITE, INSTANCES_WRITE);
    }

    @Test
    void normalizeAliases() {
        assertThat(ConsoleRoles.normalize("ADMIN")).isEqualTo(ConsoleRoles.ADMIN);
        assertThat(ConsoleRoles.normalize("ROLE_CONSOLE_OPERATOR")).isEqualTo(ConsoleRoles.OPERATOR);
        assertThat(ConsoleRoles.normalize("ops")).isEqualTo(ConsoleRoles.OPERATOR);
        assertThat(ConsoleRoles.normalize("read")).isEqualTo(ConsoleRoles.VIEWER);
    }

    @Test
    void authoritiesIncludeRoleAndPermissions() {
        var auths = ConsoleAuthoritySupport.authoritiesForRoles(ConsoleRoles.OPERATOR);
        assertThat(ConsoleAuthoritySupport.roleAuthorities(auths))
                .containsExactly("ROLE_" + ConsoleRoles.OPERATOR);
        assertThat(ConsoleAuthoritySupport.permissionAuthorities(auths))
                .contains(INSTANCES_START_STOP, SQL_EXECUTE)
                .doesNotContain(SECURITY_KEYS);
    }
}
