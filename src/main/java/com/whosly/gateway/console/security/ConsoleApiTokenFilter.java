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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Optional Bearer / X-Console-Token gate for {@code /console/api/**}.
 * <ul>
 *   <li>Blank {@code gateway.console.api-token} and {@code read-token} → open (lab default).</li>
 *   <li>{@code api-token}: read + write.</li>
 *   <li>{@code read-token}: GET/HEAD only; writes still require {@code api-token} when configured.</li>
 * </ul>
 * Does not protect static SPA assets under {@code /console}.
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
        // auth discovery endpoints stay open even in token mode
        return path.startsWith("/console/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = extractToken(request);
        String method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
        boolean readOnly = "GET".equals(method) || "HEAD".equals(method);

        boolean ok;
        if (readOnly) {
            ok = matches(apiToken, provided) || matches(readToken, provided);
            // If only read-token is configured (api blank), still allow GET with read-token
            if (!ok && apiToken.isEmpty() && matches(readToken, provided)) {
                ok = true;
            }
        } else {
            // Writes require api-token when it is configured; if only read-token exists, deny writes
            if (!apiToken.isEmpty()) {
                ok = matches(apiToken, provided);
            } else {
                ok = false;
            }
        }

        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"ok\":false,\"message\":\"Unauthorized: missing or invalid console API token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean matches(String expected, String actual) {
        return expected != null && !expected.isEmpty() && constantTimeEquals(expected, actual);
    }

    private static String extractToken(HttpServletRequest request) {
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
