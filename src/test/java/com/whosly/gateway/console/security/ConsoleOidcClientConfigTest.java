package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.Oidc;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleOidcClientConfigTest {

    @Test
    void explicitEndpointsBuildWithoutNetwork() {
        Oidc oidc = base();
        oidc.setAuthorizationUri("http://idp.example/oauth/authorize");
        oidc.setTokenUri("http://idp.example/oauth/token");
        oidc.setJwkSetUri("http://idp.example/oauth/jwks");
        oidc.setUserInfoUri("http://idp.example/oauth/userinfo");

        ClientRegistration reg = ConsoleOidcClientConfig.buildRegistration(oidc);
        assertThat(reg.getRegistrationId()).isEqualTo("console");
        assertThat(reg.getClientId()).isEqualTo("console-client");
        assertThat(reg.getProviderDetails().getAuthorizationUri())
                .isEqualTo("http://idp.example/oauth/authorize");
        assertThat(reg.getProviderDetails().getTokenUri())
                .isEqualTo("http://idp.example/oauth/token");
        assertThat(reg.getProviderDetails().getJwkSetUri())
                .isEqualTo("http://idp.example/oauth/jwks");
        assertThat(reg.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("http://idp.example/oauth/userinfo");
        assertThat(reg.getScopes()).contains("openid", "profile", "email");
    }

    @Test
    void explicitPartialEndpointsFailClearly() {
        Oidc oidc = base();
        oidc.setAuthorizationUri("http://idp.example/oauth/authorize");
        // token + jwk missing
        assertThatThrownBy(() -> ConsoleOidcClientConfig.buildRegistration(oidc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token-uri");
    }

    @Test
    void keycloakProviderUsesPathDefaultsWithoutNetwork() {
        Oidc oidc = base();
        oidc.setProvider("keycloak");
        oidc.setIssuerUri("http://localhost:8081/realms/gateway");

        ClientRegistration reg = ConsoleOidcClientConfig.buildRegistration(oidc);
        assertThat(reg.getProviderDetails().getAuthorizationUri())
                .isEqualTo("http://localhost:8081/realms/gateway/protocol/openid-connect/auth");
        assertThat(reg.getProviderDetails().getTokenUri())
                .isEqualTo("http://localhost:8081/realms/gateway/protocol/openid-connect/token");
        assertThat(reg.getProviderDetails().getJwkSetUri())
                .isEqualTo("http://localhost:8081/realms/gateway/protocol/openid-connect/certs");
    }

    @Test
    void customRegistrationIdHonored() {
        Oidc oidc = base();
        oidc.setRegistrationId("gw-console");
        oidc.setProvider("keycloak");
        ClientRegistration reg = ConsoleOidcClientConfig.buildRegistration(oidc);
        assertThat(reg.getRegistrationId()).isEqualTo("gw-console");
    }

    @Test
    void bindsOidcEndpointOverridesFromProperties() {
        org.springframework.boot.context.properties.source.MapConfigurationPropertySource source =
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        java.util.Map.of(
                                "gateway.console.auth.mode", "oidc",
                                "gateway.console.auth.oidc.issuer-uri", "http://idp/realms/lab",
                                "gateway.console.auth.oidc.client-id", "c",
                                "gateway.console.auth.oidc.authorization-uri", "http://idp/a",
                                "gateway.console.auth.oidc.token-uri", "http://idp/t",
                                "gateway.console.auth.oidc.jwk-set-uri", "http://idp/j",
                                "gateway.console.auth.oidc.role-claim", "realm_access.roles"
                        ));
        ConsoleAuthProperties props = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("gateway.console.auth",
                        org.springframework.boot.context.properties.bind.Bindable.of(ConsoleAuthProperties.class))
                .get();
        assertThat(props.getOidc().hasExplicitEndpoints()).isTrue();
        assertThat(props.getOidc().getRoleClaim()).isEqualTo("realm_access.roles");
        ClientRegistration reg = ConsoleOidcClientConfig.buildRegistration(props.getOidc());
        assertThat(reg.getProviderDetails().getAuthorizationUri()).isEqualTo("http://idp/a");
    }

    private static Oidc base() {
        Oidc oidc = new Oidc();
        oidc.setIssuerUri("http://localhost:8081/realms/gateway");
        oidc.setClientId("console-client");
        oidc.setClientSecret("secret");
        return oidc;
    }
}
