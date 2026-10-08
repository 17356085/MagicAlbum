package com.example.demo.auth.service.apple;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Service
public class AppleClientSecretService {
    private final String clientId;
    private final String teamId;
    private final String keyId;
    private final String privateKeyPem;
    private final long ttlSeconds;

    public AppleClientSecretService(
            @Value("${app.auth.oauth.apple.client-id:}") String clientId,
            @Value("${app.auth.oauth.apple.team-id:}") String teamId,
            @Value("${app.auth.oauth.apple.key-id:}") String keyId,
            @Value("${app.auth.oauth.apple.private-key:}") String privateKeyPem,
            @Value("${app.auth.oauth.apple.client-secret-ttl-seconds:15768000}") long ttlSeconds
    ) {
        this.clientId = trimToEmpty(clientId);
        this.teamId = trimToEmpty(teamId);
        this.keyId = trimToEmpty(keyId);
        this.privateKeyPem = trimToEmpty(privateKeyPem);
        this.ttlSeconds = Math.max(ttlSeconds, 300);
    }

    public static AppleClientSecretService forTest(
            String clientId,
            String teamId,
            String keyId,
            String privateKeyPem,
            long ttlSeconds
    ) {
        return new AppleClientSecretService(clientId, teamId, keyId, privateKeyPem, ttlSeconds);
    }

    public boolean isConfigured() {
        return StringUtils.hasText(clientId)
                && StringUtils.hasText(teamId)
                && StringUtils.hasText(keyId)
                && StringUtils.hasText(privateKeyPem);
    }

    public String generateClientSecret() {
        if (!isConfigured()) {
            throw new IllegalStateException("Apple OAuth client_secret 配置不完整");
        }

        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);
        ECPrivateKey privateKey = loadPrivateKey();

        return Jwts.builder()
                .header()
                .keyId(keyId)
                .and()
                .issuer(teamId)
                .subject(clientId)
                .audience().add("https://appleid.apple.com").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    private ECPrivateKey loadPrivateKey() {
        try {
            String normalized = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(normalized.getBytes(StandardCharsets.UTF_8));
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(spec);
            if (!(privateKey instanceof ECPrivateKey ecPrivateKey)) {
                throw new IllegalStateException("Apple 私钥不是有效的 EC 私钥");
            }
            return ecPrivateKey;
        } catch (Exception ex) {
            throw new IllegalStateException("Apple 私钥解析失败", ex);
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
