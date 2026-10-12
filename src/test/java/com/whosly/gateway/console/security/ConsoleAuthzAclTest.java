package com.whosly.gateway.console.security;

import com.whosly.gateway.console.persist.InstanceAclStore;
import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static com.whosly.gateway.console.security.ConsolePermission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleAuthzAclTest {

    private InstanceAclService acl;
    private ConsoleAuthz authz;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:acl-authz-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        acl = new InstanceAclService(new InstanceAclStore(new JdbcTemplate(ds)), AuthMode.FORM);
        ObjectProvider<InstanceAclService> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(acl);
        authz = new ConsoleAuthz(AuthMode.FORM, op);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void denyWhenAclMissingInstance() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "alice", "N/A",
                        ConsoleAuthoritySupport.authoritiesForRoles(ConsoleRoles.OPERATOR)));
        acl.upsertBinding("gw-1", "user", "alice", List.of(INSTANCES_READ, SQL_EXECUTE));
        assertThat(authz.canAccessInstance("gw-1", SQL_EXECUTE)).isTrue();
        assertThat(authz.canAccessInstance("gw-2", SQL_EXECUTE)).isFalse();
        assertThat(authz.canAccessInstance("gw-1", INSTANCES_START_STOP)).isFalse(); // not in ACL
    }

    @Test
    void openAlwaysAllows() {
        @SuppressWarnings("unchecked")
        ObjectProvider<InstanceAclService> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(acl);
        ConsoleAuthz open = new ConsoleAuthz(AuthMode.OPEN, op);
        assertThat(open.canAccessInstance("any", INSTANCES_DELETE)).isTrue();
    }
}
