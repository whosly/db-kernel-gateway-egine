package com.whosly.gateway.console.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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
        String m = mode != null ? mode.trim().toLowerCase() : "";
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
        /** CONSOLE_ADMIN or CONSOLE_VIEWER (comma-separated ok). */
        private String roles = "CONSOLE_VIEWER";

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRoles() { return roles; }
        public void setRoles(String roles) { this.roles = roles; }
    }

    public static class Oidc {
        private String issuerUri = "";
        private String clientId = "";
        private String clientSecret = "";
        private String scopes = "openid,profile,email";

        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getScopes() { return scopes; }
        public void setScopes(String scopes) { this.scopes = scopes; }

        public boolean isConfigured() {
            return issuerUri != null && !issuerUri.isBlank()
                    && clientId != null && !clientId.isBlank();
        }
    }
}
