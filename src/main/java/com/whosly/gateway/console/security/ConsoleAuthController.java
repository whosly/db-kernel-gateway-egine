package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Form-login JSON endpoints for the Vue SPA. Token/open modes still expose /auth/me + /auth/mode.
 * OIDC SSO entry is {@code /oauth2/authorization/{registrationId}} (default registrationId=console).
 */
@RestController
@RequestMapping("/console/api/v1/auth")
public class ConsoleAuthController {

    private final AuthMode authMode;
    private final AuthenticationManager authenticationManager;
    private final ConsoleAuditService auditService;
    private final ConsoleAuthProperties authProperties;
    private final String apiToken;
    private final String readToken;

    public ConsoleAuthController(AuthMode authMode,
                                 AuthenticationManager consoleAuthenticationManager,
                                 ConsoleAuthProperties authProperties,
                                 @Value("${gateway.console.api-token:}") String apiToken,
                                 @Value("${gateway.console.read-token:}") String readToken,
                                 @Autowired(required = false) ConsoleAuditService auditService) {
        this.authMode = authMode;
        this.authenticationManager = consoleAuthenticationManager;
        this.authProperties = authProperties;
        this.apiToken = apiToken != null ? apiToken.trim() : "";
        this.readToken = readToken != null ? readToken.trim() : "";
        this.auditService = auditService;
    }

    @GetMapping({"/mode", "/status"})
    public Map<String, Object> mode() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", authMode.name().toLowerCase());
        body.put("formLogin", authMode == AuthMode.FORM);
        body.put("oidc", authMode == AuthMode.OIDC);
        body.put("token", authMode == AuthMode.TOKEN);
        body.put("open", authMode == AuthMode.OPEN);
        if (authMode == AuthMode.OIDC) {
            String regId = authProperties.getOidc().getRegistrationId();
            body.put("registrationId", regId);
            body.put("ssoLoginUrl", "/oauth2/authorization/" + regId);
            body.put("oidcConfigured", authProperties.getOidc().isConfigured());
        }
        return body;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", authMode.name().toLowerCase());

        if (authMode == AuthMode.OPEN) {
            body.put("authenticated", true);
            body.put("username", "anonymous");
            body.put("roles", List.of("ROLE_" + ConsoleRoles.ADMIN));
            body.put("permissions", ConsoleAuthoritySupport.allPermissionsSorted());
            return body;
        }

        if (authMode == AuthMode.TOKEN) {
            return meFromToken(request, body);
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()));
        body.put("authenticated", !anonymous);
        if (anonymous) {
            body.put("username", null);
            body.put("roles", List.of());
            body.put("permissions", List.of());
            return body;
        }
        body.put("username", auth.getName());
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        body.put("roles", ConsoleAuthoritySupport.roleAuthorities(authorities));
        body.put("permissions", ConsoleAuthoritySupport.permissionAuthorities(authorities));
        return body;
    }

    private Map<String, Object> meFromToken(HttpServletRequest request, Map<String, Object> body) {
        String provided = ConsoleApiTokenFilter.extractToken(request);
        boolean apiOk = !apiToken.isEmpty() && provided != null && constantTimeEquals(apiToken, provided);
        boolean readOk = !readToken.isEmpty() && provided != null && constantTimeEquals(readToken, provided);
        if (!apiOk && !readOk) {
            body.put("authenticated", false);
            body.put("username", null);
            body.put("roles", List.of());
            body.put("permissions", List.of());
            return body;
        }
        String role = apiOk ? ConsoleRoles.ADMIN : ConsoleRoles.VIEWER;
        Collection<? extends GrantedAuthority> authorities =
                ConsoleAuthoritySupport.authoritiesForRoles(role);
        body.put("authenticated", true);
        body.put("username", apiOk ? "api-token" : "read-token");
        body.put("roles", ConsoleAuthoritySupport.roleAuthorities(authorities));
        body.put("permissions", ConsoleAuthoritySupport.permissionAuthorities(authorities));
        return body;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginBody body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        if (authMode != AuthMode.FORM) {
            throw new IllegalStateException("Form login is only available when gateway.console.auth.mode=form");
        }
        if (body == null || body.username() == null || body.username().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        try {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(body.username().trim(), body.password());
            Authentication authenticated = authenticationManager.authenticate(token);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
            HttpSession session = request.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            if (auditService != null) {
                auditService.record("auth.login", null,
                        ConsoleAuditService.detail("username", authenticated.getName(), "ok", true));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("username", authenticated.getName());
            out.put("roles", ConsoleAuthoritySupport.roleAuthorities(authenticated.getAuthorities()));
            out.put("permissions", ConsoleAuthoritySupport.permissionAuthorities(authenticated.getAuthorities()));
            return out;
        } catch (BadCredentialsException ex) {
            if (auditService != null) {
                auditService.record("auth.login.failure", null,
                        ConsoleAuditService.detail("username", body.username().trim(), "ok", false));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("message", "用户名或密码错误");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return out;
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null ? auth.getName() : null;
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        if (auditService != null) {
            auditService.record("auth.logout", null,
                    ConsoleAuditService.detail("username", user, "ok", true));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已退出");
        return out;
    }

    public record LoginBody(String username, String password) {
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
