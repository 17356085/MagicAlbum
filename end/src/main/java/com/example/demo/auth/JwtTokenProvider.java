package com.example.demo.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    @Value("${app.security.jwt.secret:change-me}")
    private String secret;

    @Value("${app.security.jwt.issuer:MagicAlbum}")
    private String issuer;

    @Value("${app.security.jwt.access-token-ttl-minutes:60}")
    private long ttlMinutes;

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
            var claims = Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token).getPayload();
            String sub = claims.getSubject();
            return sub != null ? Long.parseLong(sub) : null;
        } catch (Exception e) {
            return null;
        }
    }
}