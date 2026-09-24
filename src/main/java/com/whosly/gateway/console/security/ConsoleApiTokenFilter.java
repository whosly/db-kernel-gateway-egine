package com.whosly.gateway.console.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Blank {@code gateway.console.api-token} → open (lab default).
 * Does not protect static SPA assets under {@code /console}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ConsoleApiTokenFilter extends OncePerRequestFilter {

    private final String apiToken;

    public ConsoleApiTokenFilter(
            @Value("${gateway.console.api-token:}") String apiToken) {
        this.apiToken = apiToken != null ? apiToken.trim() : "";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (apiToken.isEmpty()) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/console/api");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = extractToken(request);
        if (provided == null || !constantTimeEquals(apiToken, provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"ok\":false,\"message\":\"Unauthorized: missing or invalid console API token\"}");
            return;
        }
        filterChain.doFilter(request, response);
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
