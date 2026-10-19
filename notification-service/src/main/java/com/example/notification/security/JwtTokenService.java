package com.example.notification.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies user access tokens without ever exposing token material to callers or logs.
 */
@Component
public final class JwtTokenService {
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final JwtParser parser;

    public Optional<java.time.Instant> findExpiration(String compactToken) {
        try {
            var claims = parser.parseSignedClaims(compactToken).getPayload();
            return Optional.ofNullable(claims.getExpiration()).map(java.util.Date::toInstant);
        } catch (Exception invalid) { return Optional.empty(); }
    }

    public JwtTokenService(
            @Value("${app.security.jwt.secret:}") String secret,
            @Value("${app.security.jwt.issuer:}") String issuer
    ) {
        String normalizedSecret = validateSecret(secret);
        String normalizedIssuer = validateIssuer(issuer);
        SecretKey key = Keys.hmacShaKeyFor(normalizedSecret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(normalizedIssuer)
                .build();
    }

    public Optional<Long> findUserId(String compactToken) {
        if (!StringUtils.hasText(compactToken)) {
            return Optional.empty();
        }

        try {
            Jws<Claims> signedClaims = parser.parseSignedClaims(compactToken.trim());
            Claims claims = signedClaims.getPayload();
            if (claims.getExpiration() == null) {
                return Optional.empty();
            }

            String subject = claims.getSubject();
            if (!StringUtils.hasText(subject)) {
                return Optional.empty();
            }

            long userId = Long.parseLong(subject);
            return userId > 0 ? Optional.of(userId) : Optional.empty();
        } catch (Exception ignored) {
            // Treat every malformed, expired, incorrectly signed, or mismatched token as unauthenticated.
            return Optional.empty();
        }
    }

    private String validateSecret(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("JWT secret is missing; configure it through the environment");
        }

        String normalized = secret.trim();
        int secretBytes = normalized.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MINIMUM_SECRET_BYTES || isObviousPlaceholder(normalized)) {
            throw new IllegalStateException("JWT secret is too weak; configure a unique secret of at least 32 bytes");
        }
        return normalized;
    }

    private String validateIssuer(String issuer) {
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalStateException("JWT issuer is missing; configure it through the environment");
        }

        String normalized = issuer.trim();
        if (normalized.length() < 3
                || normalized.length() > 128
                || normalized.chars().anyMatch(Character::isWhitespace)
                || isIssuerPlaceholder(normalized)) {
            throw new IllegalStateException("JWT issuer is invalid; configure a non-blank issuer");
        }
        return normalized;
    }

    private boolean isObviousPlaceholder(String secret) {
        String normalized = secret.toLowerCase(Locale.ROOT);
        return "change-me".equals(normalized)
                || "replace-me".equals(normalized)
                || "replace_with_a_secure_jwt_secret_at_least_32_bytes_long".equals(normalized)
                || normalized.contains("change_me")
                || normalized.contains("change-me")
                || normalized.contains("devsecret")
                || secret.chars().distinct().count() < 3;
    }

    private boolean isIssuerPlaceholder(String issuer) {
        String normalized = issuer.toLowerCase(Locale.ROOT);
        return "change-me".equals(normalized)
                || "replace-me".equals(normalized)
                || "default".equals(normalized);
    }
}
