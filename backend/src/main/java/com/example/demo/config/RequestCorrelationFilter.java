package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = normalize(request.getHeader(REQUEST_ID));
        if (requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        long started = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID, requestId);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            int status = failed && !response.isCommitted() && response.getStatus() < 400
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            org.slf4j.LoggerFactory.getLogger(RequestCorrelationFilter.class).info(
                    "request_id={} method={} route={} status={} duration_ms={} service=backend",
                    requestId, request.getMethod(), routePattern(request),
                    status, durationMs
            );
            MDC.remove("requestId");
        }
    }

    private String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String value && !value.isBlank()
                && value.startsWith("/") && !value.contains("?")) {
            return value;
        }
        return "unmatched";
    }

    private String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]+")) {
            return "";
        }
        return value;
    }
}
