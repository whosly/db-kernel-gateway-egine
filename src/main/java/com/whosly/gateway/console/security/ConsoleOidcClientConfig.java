package com.whosly.gateway.console.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * OIDC client registration from {@code gateway.console.auth.oidc.*} when mode=oidc.
 * Uses explicit endpoints derived from issuer-uri (no network discovery at startup),
 * so unit tests and offline boots stay green. Real IdP metadata discovery can be
 * added later via ClientRegistrations.fromIssuerLocation when desired.
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.console.auth", name = "mode", havingValue = "oidc")
public class ConsoleOidcClientConfig {

    @Bean
    public ClientRegistrationRepository consoleOidcClientRegistrationRepository(
            ConsoleAuthProperties authProperties) {
        ConsoleAuthProperties.Oidc oidc = authProperties.getOidc();
        if (!oidc.isConfigured()) {
            throw new IllegalStateException(
                    "gateway.console.auth.mode=oidc requires oidc.issuer-uri and oidc.client-id");
        }
        String issuer = oidc.getIssuerUri().replaceAll("/$", "");
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId("console")
                .clientId(oidc.getClientId())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .issuerUri(issuer)
                .authorizationUri(issuer + "/protocol/openid-connect/auth")
                .tokenUri(issuer + "/protocol/openid-connect/token")
                .jwkSetUri(issuer + "/protocol/openid-connect/certs")
                .userInfoUri(issuer + "/protocol/openid-connect/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Console OIDC");
        if (oidc.getClientSecret() != null && !oidc.getClientSecret().isBlank()) {
            builder.clientSecret(oidc.getClientSecret());
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        if (oidc.getScopes() != null && !oidc.getScopes().isBlank()) {
            builder.scope(oidc.getScopes().split("[,\\s]+"));
        } else {
            builder.scope("openid", "profile", "email");
        }
        return new InMemoryClientRegistrationRepository(builder.build());
    }
}
