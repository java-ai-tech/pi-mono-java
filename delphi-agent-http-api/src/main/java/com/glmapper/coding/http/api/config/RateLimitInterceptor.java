package com.glmapper.coding.http.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.tenant.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP interceptor that enforces per-namespace rate limiting on chat endpoints.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // Only check POST requests with a body
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String namespace = extractNamespace(request);
        if (namespace == null || namespace.isBlank()) {
            return true; // Let the controller handle missing namespace validation
        }

        if (!rateLimiter.tryAcquire(namespace)) {
            writeRateLimitResponse(response);
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private String extractNamespace(HttpServletRequest request) {
        if (!(request instanceof CachedBodyHttpServletRequest)) {
            return null;
        }
        try {
            byte[] body = request.getInputStream().readAllBytes();
            if (body.length == 0) {
                return null;
            }
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object ns = parsed.get("namespace");
            return ns != null ? ns.toString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String json = objectMapper.writeValueAsString(
                Map.of("error", "rate_limited", "message", "Too many requests. Please try again later."));
        response.getWriter().write(json);
        response.getWriter().flush();
    }
}
