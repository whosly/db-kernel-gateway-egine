package com.whosly.gateway.console.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Console authentication modes: open | token | form | oidc.
 * Bound from {@code gateway.console.auth.*}.
 */
@ConfigurationProperties(prefix = "gateway.console.auth")
public class ConsoleAuthProperties {

    /**
     * open | token | form | oidc. Blank = auto (token if api-token/read-token set, else open).
     */
    private String mode = "";

    private List<UserAccount> users = new ArrayList<>();

    private final Oidc oidc = new Oidc();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<UserAccount> getUsers() {
        return users;
    }

    public void setUsers(List<UserAccount> users) {
        this.users = users != null ? users : new ArrayList<>();
    }

    public Oidc getOidc() {
        return oidc;
    }

    public AuthMode resolvedMode(String apiToken, String readToken) {
        String m = mode != null ? mode.trim().toLowerCase(Locale.ROOT) : "";
        if (!m.isEmpty()) {
            return AuthMode.from(m);
        }
        boolean hasToken = (apiToken != null && !apiToken.isBlank())
                || (readToken != null && !readToken.isBlank());
        return hasToken ? AuthMode.TOKEN : AuthMode.OPEN;
    }

    public enum AuthMode {
        OPEN, TOKEN, FORM, OIDC;

        static AuthMode from(String raw) {
            return switch (raw) {
                case "open" -> OPEN;
                case "token" -> TOKEN;
                case "form" -> FORM;
                case "oidc" -> OIDC;
                default -> throw new IllegalArgumentException(
                        "Unknown gateway.console.auth.mode='" + raw
                                + "' (expected: open|token|form|oidc)");
            };
        }
    }

    public static class UserAccount {
        private String username;
        private String password;
        /** CONSOLE_ADMIN / CONSOLE_OPERATOR / CONSOLE_VIEWER (comma-separated ok). */
        private String roles = "CONSOLE_VIEWER";

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRoles() { return roles; }
        public void setRoles(String roles) { this.roles = roles; }
    }

    /**
     * OIDC / OAuth2 client settings for console SSO.
     *
     * <p>Endpoint resolution order:
     * <ol>
     *   <li>If any of authorization-uri / token-uri / jwk-set-uri is set → use explicit URIs
     *       (user-info-uri optional).</li>
     *   <li>Else if {@code provider=keycloak} → Keycloak path defaults under issuer-uri.</li>
     *   <li>Else → Spring {@code ClientRegistrations.fromIssuerLocation(issuer-uri)}
     *       (requires network; fails clearly if discovery unreachable).</li>
     * </ol>
     * Unit tests should set explicit URIs (no network).
     */
    public static class Oidc {
        /** Spring registration id → login URL {@code /oauth2/authorization/{id}}. */
        private String registrationId = "console";
        private String issuerUri = "";
        private String clientId = "";
        private String clientSecret = "";
        private String scopes = "openid,profile,email";
        /** Optional: keycloak | generic (blank). */
        private String provider = "";
        private String authorizationUri = "";
        private String tokenUri = "";
        private String jwkSetUri = "";
        private String userInfoUri = "";
        private String userNameAttribute = "sub";
        /**
         * Claim holding role/group values. Supports dotted paths for nested maps,
         * e.g. {@code realm_access.roles}, {@code groups}, {@code roles}.
         */
        private String roleClaim = "roles";
        /** Comma-separated IdP values that map to CONSOLE_ADMIN. */
        private String adminRoleValues = "CONSOLE_ADMIN,admin,console-admin";
        /** Comma-separated IdP values that map to CONSOLE_OPERATOR. */
        private String operatorRoleValues = "CONSOLE_OPERATOR,operator,console-operator";
        /** Comma-separated IdP values that map to CONSOLE_VIEWER. */
        private String viewerRoleValues = "CONSOLE_VIEWER,viewer,console-viewer";

        public String getRegistrationId() { return registrationId; }
        public void setRegistrationId(String registrationId) {
            this.registrationId = registrationId != null && !registrationId.isBlank()
                    ? registrationId.trim() : "console";
        }
        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getScopes() { return scopes; }
        public void setScopes(String scopes) { this.scopes = scopes; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getAuthorizationUri() { return authorizationUri; }
        public void setAuthorizationUri(String authorizationUri) { this.authorizationUri = authorizationUri; }
        public String getTokenUri() { return tokenUri; }
        public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }
        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
        public String getUserInfoUri() { return userInfoUri; }
        public void setUserInfoUri(String userInfoUri) { this.userInfoUri = userInfoUri; }
        public String getUserNameAttribute() { return userNameAttribute; }
        public void setUserNameAttribute(String userNameAttribute) {
            this.userNameAttribute = userNameAttribute != null && !userNameAttribute.isBlank()
                    ? userNameAttribute.trim() : "sub";
        }
        public String getRoleClaim() { return roleClaim; }
        public void setRoleClaim(String roleClaim) { this.roleClaim = roleClaim; }
        public String getAdminRoleValues() { return adminRoleValues; }
        public void setAdminRoleValues(String adminRoleValues) { this.adminRoleValues = adminRoleValues; }
        public String getOperatorRoleValues() { return operatorRoleValues; }
        public void setOperatorRoleValues(String operatorRoleValues) { this.operatorRoleValues = operatorRoleValues; }
        public String getViewerRoleValues() { return viewerRoleValues; }
        public void setViewerRoleValues(String viewerRoleValues) { this.viewerRoleValues = viewerRoleValues; }

        public boolean isConfigured() {
            return issuerUri != null && !issuerUri.isBlank()
                    && clientId != null && !clientId.isBlank();
        }

        /** True when at least one endpoint override is present. */
        public boolean hasExplicitEndpoints() {
            return hasText(authorizationUri) || hasText(tokenUri) || hasText(jwkSetUri);
        }

        public boolean isKeycloakProvider() {
            return provider != null && "keycloak".equalsIgnoreCase(provider.trim());
        }

        public List<String> adminRoleValueList() {
            return splitCsv(adminRoleValues);
        }

        public List<String> operatorRoleValueList() {
            return splitCsv(operatorRoleValues);
        }

        public List<String> viewerRoleValueList() {
            return splitCsv(viewerRoleValues);
        }

        public List<String> scopeList() {
            if (scopes == null || scopes.isBlank()) {
                return List.of("openid", "profile", "email");
            }
            return splitCsv(scopes);
        }

        private static List<String> splitCsv(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return Arrays.stream(raw.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }
}
