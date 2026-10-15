package com.example.demo.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class JwtTokenProvider {

    private static final Set<String> INSECURE_SECRETS = Set.of(
            "",
            "change-me",
            "MagicAlbumDevSecretKey_ChangeMe_PleaseReplace_0123456789ABCDEF0123456789"
    );

    @Value("${app.security.jwt.secret:}")
    private String secret;

    @Value("${app.security.jwt.issuer:MagicAlbum}")
    private String issuer;

    @Value("${app.security.jwt.access-token-ttl-minutes:60}")
    private long ttlMinutes;

    @PostConstruct
    public void validateConfiguration() {
        String normalizedSecret = secret == null ? "" : secret.trim();
        if (!StringUtils.hasText(normalizedSecret) || INSECURE_SECRETS.contains(normalizedSecret)
                || isObviousPlaceholder(normalizedSecret)) {
            throw new IllegalStateException("JWT secret 未配置或仍为默认弱密钥，请通过 app.security.jwt.secret 或 JWT_SECRET 显式设置");
        }
        if (normalizedSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret 长度不足，至少需要 32 字节");
        }
        if (!StringUtils.hasText(issuer) || issuer.trim().length() < 3
                || issuer.trim().length() > 128
                || issuer.trim().chars().anyMatch(Character::isWhitespace)
                || "default".equalsIgnoreCase(issuer.trim())
                || "change-me".equalsIgnoreCase(issuer.trim())) {
            throw new IllegalStateException("JWT issuer 未配置或无效，请通过 JWT_ISSUER 显式设置");
        }
        if (ttlMinutes <= 0 || ttlMinutes > 1440) {
            throw new IllegalStateException("JWT access token 有效期不合理，建议设置在 1 到 1440 分钟之间");
        }
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlMinutes * 60);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("username", username)
                .signWith(getKey())
                .compact();
    }

    public Long getUserIdFromToken(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(getKey())
                    .requireIssuer(issuer.trim())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String sub = claims.getSubject();
            if (claims.getExpiration() == null || sub == null) {
                return null;
            }
            long userId = Long.parseLong(sub);
            return userId > 0 ? userId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isObviousPlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("change_me")
                || normalized.contains("change-me")
                || normalized.contains("replace_me")
                || normalized.contains("replace-me")
                || normalized.contains("devsecret")
                || value.chars().distinct().count() < 3;
    }
}
