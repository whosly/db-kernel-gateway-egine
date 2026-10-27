package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static com.whosly.gateway.console.security.ConsolePermission.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConsoleAuthzTest {

    @SuppressWarnings("unchecked")
    private static ConsoleAuthz authz(AuthMode mode) {
        ObjectProvider<InstanceAclService> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(null);
        return new ConsoleAuthz(mode, op);
    }


    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void openModeAlwaysGrants() {
        ConsoleAuthz authz = authz(AuthMode.OPEN);
        SecurityContextHolder.clearContext();
        assertThat(authz.has(SECURITY_KEYS)).isTrue();
        assertThat(authz.has(INSTANCES_DELETE)).isTrue();
    }

    @Test
    void viewerCannotPutKeysOrDeleteInstance() {
        ConsoleAuthz authz = authz(AuthMode.FORM);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "viewer", "N/A",
                        ConsoleAuthoritySupport.authoritiesForRoles(ConsoleRoles.VIEWER)));
        assertThat(authz.has(INSTANCES_READ)).isTrue();
        assertThat(authz.has(SECURITY_KEYS)).isFalse();
        assertThat(authz.has(INSTANCES_DELETE)).isFalse();
        assertThat(authz.has(INSTANCES_START_STOP)).isFalse();
    }

    @Test
    void operatorCanStartButNotKeys() {
        ConsoleAuthz authz = authz(AuthMode.FORM);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "operator", "N/A",
                        ConsoleAuthoritySupport.authoritiesForRoles(ConsoleRoles.OPERATOR)));
        assertThat(authz.has(INSTANCES_START_STOP)).isTrue();
        assertThat(authz.has(SQL_EXECUTE)).isTrue();
        assertThat(authz.has(SECURITY_KEYS)).isFalse();
        assertThat(authz.has(INSTANCES_DELETE)).isFalse();
    }

    @Test
    void adminCanKeys() {
        ConsoleAuthz authz = authz(AuthMode.FORM);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin", "N/A",
                        ConsoleAuthoritySupport.authoritiesForRoles(ConsoleRoles.ADMIN)));
        assertThat(authz.has(SECURITY_KEYS)).isTrue();
        assertThat(authz.has(RISK_WRITE)).isTrue();
        assertThat(authz.has(INSTANCES_DELETE)).isTrue();
    }
}
