package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a minimal MVC + Security context with OIDC mode and explicit endpoint
 * properties (no external IdP / no issuer discovery).
 */
class ConsoleOidcSecurityChainTest {

    @Test
    void oidcSecurityFilterChainLoadsWithExplicitRegistration() {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("oidc-test", Map.of(
                "gateway.console.auth.mode", "oidc",
                "gateway.console.auth.oidc.issuer-uri", "http://idp.example/realms/lab",
                "gateway.console.auth.oidc.client-id", "console",
                "gateway.console.auth.oidc.client-secret", "secret",
                "gateway.console.auth.oidc.authorization-uri", "http://idp.example/auth",
                "gateway.console.auth.oidc.token-uri", "http://idp.example/token",
                "gateway.console.auth.oidc.jwk-set-uri", "http://idp.example/jwks",
                "gateway.console.auth.oidc.user-info-uri", "http://idp.example/userinfo",
                "gateway.console.api-token", "",
                "gateway.console.read-token", ""
        )));
        ctx.register(MvcSupportConfig.class, ConsoleSecurityConfig.class, ConsoleOidcClientConfig.class);
        try {
            ctx.refresh();
            assertThat(ctx.getBean(AuthMode.class)).isEqualTo(AuthMode.OIDC);
            SecurityFilterChain chain = ctx.getBean("consoleSecurityFilterChain", SecurityFilterChain.class);
            assertThat(chain).isNotNull();
            assertThat(chain.getFilters()).isNotEmpty();
            // OAuth2 login filter present when oidc configured
            boolean hasOauth2 = chain.getFilters().stream()
                    .anyMatch(f -> f.getClass().getName().contains("OAuth2"));
            assertThat(hasOauth2).as("expected OAuth2-related filter in chain").isTrue();
            ClientRegistrationRepository repo = ctx.getBean(ClientRegistrationRepository.class);
            assertThat(repo.findByRegistrationId("console").getClientId()).isEqualTo("console");
            assertThat(ctx.getBean(ConsoleOidcRoleMapper.class)).isNotNull();
        } finally {
            ctx.close();
        }
    }

    @Test
    void oidcModeWithoutIssuerFallsBackButStillResolvesMode() {
        ConsoleAuthProperties props = new ConsoleAuthProperties();
        props.setMode("oidc");
        ConsoleSecurityConfig cfg = new ConsoleSecurityConfig(props, "", "");
        assertThat(cfg.consoleAuthMode()).isEqualTo(AuthMode.OIDC);
        assertThat(props.getOidc().isConfigured()).isFalse();
    }

    @Configuration
    @EnableWebMvc
    static class MvcSupportConfig {
        @Bean(name = "mvcHandlerMappingIntrospector")
        HandlerMappingIntrospector mvcHandlerMappingIntrospector() {
            return new HandlerMappingIntrospector();
        }
    }
}
