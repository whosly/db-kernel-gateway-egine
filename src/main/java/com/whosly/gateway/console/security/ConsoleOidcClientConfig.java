package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.Oidc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

/**
 * Builds the console OAuth2 {@link ClientRegistration} for SSO.
 *
 * <p>Resolution (see {@link Oidc}):
 * <ol>
 *   <li>Explicit endpoint overrides when any of authorization/token/jwk-set URI is set</li>
 *   <li>Keycloak path defaults when {@code provider=keycloak} and overrides blank</li>
 *   <li>Otherwise {@link ClientRegistrations#fromIssuerLocation} (needs network)</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.console.auth", name = "mode", havingValue = "oidc")
public class ConsoleOidcClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsoleOidcClientConfig.class);

    @Bean
    public ClientRegistrationRepository consoleClientRegistrationRepository(ConsoleAuthProperties auth) {
        Oidc oidc = auth.getOidc();
        if (!oidc.isConfigured()) {
            log.warn("OIDC mode without issuer-uri/client-id — empty ClientRegistrationRepository "
                    + "(Security falls back to form-style chain)");
            return new InMemoryClientRegistrationRepository(java.util.List.of());
        }
        ClientRegistration registration = buildRegistration(oidc);
        log.info("Console OIDC ClientRegistration id={} issuer={} authUri={}",
                registration.getRegistrationId(),
                registration.getProviderDetails().getIssuerUri(),
                registration.getProviderDetails().getAuthorizationUri());
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    public ConsoleOidcRoleMapper consoleOidcRoleMapper(ConsoleAuthProperties auth) {
        return new ConsoleOidcRoleMapper(auth.getOidc());
    }

    static ClientRegistration buildRegistration(Oidc oidc) {
        String registrationId = oidc.getRegistrationId();
        if (oidc.hasExplicitEndpoints()) {
            return buildExplicit(oidc, registrationId);
        }
        if (oidc.isKeycloakProvider()) {
            return buildKeycloakDefaults(oidc, registrationId);
        }
        return buildFromIssuerDiscovery(oidc, registrationId);
    }

    private static ClientRegistration buildFromIssuerDiscovery(Oidc oidc, String registrationId) {
        String issuer = trimSlash(oidc.getIssuerUri());
        try {
            ClientRegistration discovered = ClientRegistrations.fromIssuerLocation(issuer)
                    .registrationId(registrationId)
                    .clientId(oidc.getClientId())
                    .clientSecret(nullToEmpty(oidc.getClientSecret()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .scope(oidc.scopeList().toArray(String[]::new))
                    .userNameAttributeName(oidc.getUserNameAttribute())
                    .clientName("Console SSO")
                    .build();
            log.info("OIDC ClientRegistration via issuer discovery: {}", issuer);
            return discovered;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "OIDC issuer discovery failed for '" + issuer
                            + "'. Set explicit gateway.console.auth.oidc.authorization-uri / "
                            + "token-uri / jwk-set-uri (and optional user-info-uri), "
                            + "or set provider=keycloak for Keycloak path defaults. Cause: "
                            + e.getMessage(),
                    e);
        }
    }

    private static ClientRegistration buildExplicit(Oidc oidc, String registrationId) {
        requireUri(oidc.getAuthorizationUri(), "authorization-uri");
        requireUri(oidc.getTokenUri(), "token-uri");
        requireUri(oidc.getJwkSetUri(), "jwk-set-uri");
        var builder = ClientRegistration.withRegistrationId(registrationId)
                .clientId(oidc.getClientId())
                .clientSecret(nullToEmpty(oidc.getClientSecret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(oidc.scopeList().toArray(String[]::new))
                .authorizationUri(oidc.getAuthorizationUri().trim())
                .tokenUri(oidc.getTokenUri().trim())
                .jwkSetUri(oidc.getJwkSetUri().trim())
                .issuerUri(trimSlash(oidc.getIssuerUri()))
                .userNameAttributeName(oidc.getUserNameAttribute())
                .clientName("Console SSO");
        if (StringUtils.hasText(oidc.getUserInfoUri())) {
            builder.userInfoUri(oidc.getUserInfoUri().trim());
        }
        log.info("OIDC ClientRegistration via explicit endpoint overrides");
        return builder.build();
    }

    private static ClientRegistration buildKeycloakDefaults(Oidc oidc, String registrationId) {
        String issuer = trimSlash(oidc.getIssuerUri());
        log.info("OIDC ClientRegistration via Keycloak path defaults under {}", issuer);
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId(oidc.getClientId())
                .clientSecret(nullToEmpty(oidc.getClientSecret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(oidc.scopeList().toArray(String[]::new))
                .authorizationUri(issuer + "/protocol/openid-connect/auth")
                .tokenUri(issuer + "/protocol/openid-connect/token")
                .jwkSetUri(issuer + "/protocol/openid-connect/certs")
                .userInfoUri(issuer + "/protocol/openid-connect/userinfo")
                .issuerUri(issuer)
                .userNameAttributeName(oidc.getUserNameAttribute())
                .clientName("Console SSO")
                .build();
    }

    private static void requireUri(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "OIDC explicit endpoints require gateway.console.auth.oidc." + name
                            + " when any endpoint override is set");
        }
    }

    private static String trimSlash(String uri) {
        if (uri == null) {
            return "";
        }
        String t = uri.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
