package com.example.notification.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

@Component
public final class InternalServiceAuthenticationFilter extends OncePerRequestFilter {
    public static final String INTERNAL_SERVICE_AUTHORITY = "INTERNAL_SERVICE";
    public static final String INTERNAL_CREDENTIAL_HEADER = "X-Internal-Service-Token";
    private static final String INTERNAL_PATH = "/api/v1/notifications/internal";

    private final byte[] expectedCredential;

    public InternalServiceAuthenticationFilter(
            @Value("${app.security.internal-service-token:}") String credential
    ) {
        this.expectedCredential = validateCredential(credential);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (INTERNAL_PATH.equals(requestPath(request))
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String presentedCredential = request.getHeader(INTERNAL_CREDENTIAL_HEADER);
            if (isValidCredential(presentedCredential)) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "internal-service",
                                null,
                                List.of(new SimpleGrantedAuthority(INTERNAL_SERVICE_AUTHORITY))
                        )
                );
            }
        }
        filterChain.doFilter(request, response);
    }

    private String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty()
                ? requestUri
                : requestUri.substring(contextPath.length());
    }

    private boolean isValidCredential(String presentedCredential) {
        if (!StringUtils.hasText(presentedCredential)) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedCredential,
                presentedCredential.getBytes(StandardCharsets.UTF_8)
        );
    }

    private byte[] validateCredential(String credential) {
        if (!StringUtils.hasText(credential)) {
            throw new IllegalStateException(
                    "Internal service credential is missing; configure it through the environment"
            );
        }

        String normalized = credential.trim();
        if (isObviousPlaceholder(normalized)) {
            throw new IllegalStateException(
                    "Internal service credential is a placeholder; configure a unique secret"
            );
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private boolean isObviousPlaceholder(String credential) {
        String normalized = credential.toLowerCase(Locale.ROOT);
        return "change-me".equals(normalized)
                || "changeme".equals(normalized)
                || "replace-me".equals(normalized)
                || "replaceme".equals(normalized)
                || "default".equals(normalized)
                || "password".equals(normalized)
                || "secret".equals(normalized)
                || "token".equals(normalized)
                || "internal-service-token".equals(normalized)
                || normalized.contains("change_me")
                || normalized.contains("change-me")
                || normalized.contains("replace_me")
                || normalized.contains("replace-me")
                || normalized.contains("replace_with")
                || normalized.contains("replace-with")
                || normalized.contains("placeholder")
                || normalized.contains("devsecret");
    }
}
