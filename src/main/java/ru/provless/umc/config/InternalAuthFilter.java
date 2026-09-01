package ru.provless.umc.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates inbound HTTP traffic to {@code /internal/*} routes.
 *
 * umc-integration-service exposes only internal endpoints (precheck), all of which must
 * carry an {@code X-Internal-Auth} header matching the shared {@code INTERNAL_API_KEY} —
 * the same mesh-wide secret already used between auth-service and phone-auth.
 *
 * No public Traefik route exists for this service; this filter is defense-in-depth on
 * top of network isolation.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Auth";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final String expectedKey;

    public InternalAuthFilter(@Value("${internal.api-key}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(INTERNAL_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || provided.isBlank() || !constantTimeEquals(provided, expectedKey)) {
            log.warn("Unauthorized /internal request: path={} ip={} header_present={}",
                    path, request.getRemoteAddr(), provided != null);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error_code\":\"INTERNAL_AUTH_REQUIRED\",\"message\":\"Missing or invalid X-Internal-Auth header\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
