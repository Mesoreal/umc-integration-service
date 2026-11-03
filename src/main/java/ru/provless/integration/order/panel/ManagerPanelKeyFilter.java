package ru.provless.integration.order.panel;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The panel's API ({@code /manager-panel/api/**}) answers only with {@code X-Panel-Key} = {@code manager-panel.key}: the page takes
 * it from its own address, which only Chatwoot's staff see. No key configured — the API is closed.
 */
@Slf4j
@Component
public class ManagerPanelKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Panel-Key";
    static final String API_PREFIX = "/manager-panel/api/";

    private final byte[] key;

    public ManagerPanelKeyFilter(@Value("${manager-panel.key:}") String key) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !StringUtils.cleanPath(request.getRequestURI() == null ? "" : request.getRequestURI()).startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (key.length == 0 || provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), key)) {
            log.warn("Manager panel API without a valid key: path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error_code\":\"PANEL_KEY_REQUIRED\",\"message\":\"Missing or invalid X-Panel-Key\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
