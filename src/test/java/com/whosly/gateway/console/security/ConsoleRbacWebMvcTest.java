package com.whosly.gateway.console.security;

import com.whosly.gateway.console.GatewayInstance;
import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import com.whosly.gateway.controller.console.ConsoleApiController;
import com.whosly.gateway.controller.console.ConsoleApiModels.MaskingKeyBody;
import com.whosly.gateway.controller.console.ConsoleInstanceApiController;
import com.whosly.gateway.controller.console.ConsoleSecurityApiController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static com.whosly.gateway.console.security.ConsolePermission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RBAC security scenarios without full Boot context.
 * Enforcement decision is {@link ConsoleAuthz} (used by {@code @PreAuthorize} in the app);
 * controllers are invoked only when authz grants.
 */
class ConsoleRbacWebMvcTest {

    private final ConsoleApiController api = Mockito.mock(ConsoleApiController.class);
    private final ConsoleSecurityApiController securityApi = new ConsoleSecurityApiController(api);
    private final ConsoleInstanceApiController instanceApi = new ConsoleInstanceApiController(api);
    private final ConsoleAuthz authz = new ConsoleAuthz(AuthMode.FORM);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void asRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        role.toLowerCase(), "N/A",
                        ConsoleAuthoritySupport.authoritiesForRoles(role)));
    }

    private void require(String permission, Runnable action) {
        if (!authz.has(permission)) {
            throw new AccessDeniedException("Forbidden: missing " + permission);
        }
        action.run();
    }

    private <T> T requireCall(String permission, java.util.concurrent.Callable<T> action) throws Exception {
        if (!authz.has(permission)) {
            throw new AccessDeniedException("Forbidden: missing " + permission);
        }
        return action.call();
    }

    @Test
    void viewerCannotPutMaskingKey() {
        asRole(ConsoleRoles.VIEWER);
        assertThatThrownBy(() -> require(SECURITY_KEYS,
                () -> securityApi.putMaskingKey(new MaskingKeyBody("k", "YQ==", null, null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void viewerCannotDeleteInstance() {
        asRole(ConsoleRoles.VIEWER);
        assertThatThrownBy(() -> require(INSTANCES_DELETE, () -> instanceApi.deleteInstance("gw-1")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void operatorCanStart() throws Exception {
        asRole(ConsoleRoles.OPERATOR);
        GatewayInstance gi = Mockito.mock(GatewayInstance.class);
        when(api.startInstance(anyString())).thenReturn(gi);
        assertThat(requireCall(INSTANCES_START_STOP, () -> instanceApi.startInstance("gw-1"))).isSameAs(gi);
    }

    @Test
    void operatorCannotPutMaskingKey() {
        asRole(ConsoleRoles.OPERATOR);
        assertThatThrownBy(() -> require(SECURITY_KEYS,
                () -> securityApi.putMaskingKey(new MaskingKeyBody("k", "YQ==", null, null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminCanPutMaskingKey() throws Exception {
        asRole(ConsoleRoles.ADMIN);
        when(api.putMaskingKey(any())).thenReturn(Map.of("configured", true));
        assertThat(requireCall(SECURITY_KEYS,
                () -> securityApi.putMaskingKey(new MaskingKeyBody("k", "YQ==", null, null))))
                .containsEntry("configured", true);
    }
}
