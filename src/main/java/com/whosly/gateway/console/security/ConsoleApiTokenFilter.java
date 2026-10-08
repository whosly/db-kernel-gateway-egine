package com.whosly.gateway.console.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Optional Bearer / X-Console-Token gate for {@code /console/api/**}.
 * <ul>
 *   <li>Blank {@code gateway.console.api-token} and {@code read-token} → open (lab default).</li>
 *   <li>{@code api-token}: ADMIN permissions (read + write).</li>
 *   <li>{@code read-token}: VIEWER permissions; GET/HEAD only; writes still require {@code api-token}.</li>
 * </ul>
 * Does not protect static SPA assets under {@code /console}.
 * On success, installs a {@link SecurityContextHolder} authentication so method security can enforce RBAC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ConsoleApiTokenFilter extends OncePerRequestFilter {

    private final String apiToken;
    private final String readToken;
    private final AuthMode authMode;

    public ConsoleApiTokenFilter(
            @Value("${gateway.console.api-token:}") String apiToken,
            @Value("${gateway.console.read-token:}") String readToken) {
        this(apiToken, readToken, null);
    }

    @Autowired
    public ConsoleApiTokenFilter(
            @Value("${gateway.console.api-token:}") String apiToken,
            @Value("${gateway.console.read-token:}") String readToken,
            @Autowired(required = false) AuthMode authMode) {
        this.apiToken = apiToken != null ? apiToken.trim() : "";
        this.readToken = readToken != null ? readToken.trim() : "";
        this.authMode = authMode;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only enforce in TOKEN mode (or auto-token when AuthMode bean absent + tokens set)
        if (authMode != null && authMode != AuthMode.TOKEN) {
            return true;
        }
        if (apiToken.isEmpty() && readToken.isEmpty()) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/console/api")) {
            return true;
        }
        // auth discovery endpoints stay open even in token mode (me resolves token if present)
        return path.startsWith("/console/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = extractToken(request);
        String method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
        boolean readOnly = "GET".equals(method) || "HEAD".equals(method);

        boolean apiOk = matches(apiToken, provided);
        boolean readOk = matches(readToken, provided);

        boolean ok;
        String role;
        if (readOnly) {
            ok = apiOk || readOk;
            role = apiOk ? ConsoleRoles.ADMIN : ConsoleRoles.VIEWER;
        } else {
            if (!apiToken.isEmpty()) {
                ok = apiOk;
                role = ConsoleRoles.ADMIN;
            } else {
                ok = false;
                role = ConsoleRoles.VIEWER;
            }
        }

        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                            + "\"detail\":\"Unauthorized: missing or invalid console API token\","
                            + "\"code\":\"UNAUTHORIZED\"}");
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        apiOk ? "api-token" : "read-token",
                        "N/A",
                        ConsoleAuthoritySupport.authoritiesForRoles(role));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean matches(String expected, String actual) {
        return expected != null && !expected.isEmpty() && constantTimeEquals(expected, actual);
    }

    static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String h = header.trim();
            if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return h.substring(7).trim();
            }
        }
        String alt = request.getHeader("X-Console-Token");
        return alt != null ? alt.trim() : null;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
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
