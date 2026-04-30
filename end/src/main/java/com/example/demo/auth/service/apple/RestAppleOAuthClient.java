package com.example.demo.auth.service.apple;

import com.example.demo.auth.dto.apple.AppleIdTokenClaims;
import com.example.demo.auth.dto.apple.AppleJwk;
import com.example.demo.auth.dto.apple.AppleJwkSetResponse;
import com.example.demo.auth.dto.apple.AppleJwtHeader;
import com.example.demo.auth.dto.apple.AppleTokenResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

@Component
public class RestAppleOAuthClient implements AppleOAuthClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AppleClientSecretService appleClientSecretService;
    private final AppleJwksClient appleJwksClient;
    private final String clientId;
    private final String redirectUri;

    public RestAppleOAuthClient(
            AppleClientSecretService appleClientSecretService,
            AppleJwksClient appleJwksClient,
            @Value("${app.auth.oauth.apple.client-id:}") String clientId,
            @Value("${app.auth.oauth.apple.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/apple}") String redirectUri
    ) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
        this.appleClientSecretService = appleClientSecretService;
        this.appleJwksClient = appleJwksClient;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
    }

    @Override
    public AppleTokenResponse exchangeCode(String code) {
        if (clientId.isBlank() || !appleClientSecretService.isConfigured()) {
            throw new IllegalStateException("Apple OAuth client 配置不完整");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", appleClientSecretService.generateClientSecret());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        AppleTokenResponse response = restClient.post()
                .uri("https://appleid.apple.com/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(AppleTokenResponse.class);

        if (response == null || response.getIdToken() == null || response.getIdToken().isBlank()) {
            throw new IllegalStateException("Apple token 获取失败");
        }
        return response;
    }

    @Override
    public AppleIdTokenClaims parseIdToken(String idToken) {
        try {
            String[] segments = idToken.split("\\.");
            if (segments.length < 3) {
                throw new IllegalStateException("Apple id_token 格式无效");
            }
            AppleJwtHeader header = objectMapper.readValue(Base64.getUrlDecoder().decode(segments[0]), AppleJwtHeader.class);
            RSAPublicKey publicKey = resolvePublicKey(header);
            Claims jwtClaims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();

            validateClaims(jwtClaims);

            AppleIdTokenClaims claims = new AppleIdTokenClaims();
            claims.setIss(jwtClaims.getIssuer());
            claims.setAud(resolveAudience(jwtClaims));
            claims.setSub(jwtClaims.getSubject());
            claims.setEmail(jwtClaims.get("email", String.class));
            claims.setEmailVerified(parseBoolean(jwtClaims.get("email_verified")));
            claims.setPrivateEmail(parseBoolean(jwtClaims.get("is_private_email")));
            if (claims.getSub() == null || claims.getSub().isBlank()) {
                throw new IllegalStateException("Apple id_token 缺少用户标识");
            }
            return claims;
        } catch (Exception ex) {
            throw new IllegalStateException("Apple id_token 解析失败", ex);
        }
    }

    private RSAPublicKey resolvePublicKey(AppleJwtHeader header) {
        if (header == null || header.getKid() == null || header.getKid().isBlank()) {
            throw new IllegalStateException("Apple id_token header 缺少 kid");
        }

        AppleJwkSetResponse jwkSet = appleJwksClient.fetchKeys();
        AppleJwk jwk = jwkSet.getKeys().stream()
                .filter(item -> header.getKid().equals(item.getKid()))
                .filter(item -> item.getAlg() == null || item.getAlg().isBlank() || header.getAlg() == null || header.getAlg().isBlank() || header.getAlg().equals(item.getAlg()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到匹配的 Apple JWKS 公钥"));

        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.getN()));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.getE()));
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            if (!(publicKey instanceof RSAPublicKey rsaPublicKey)) {
                throw new IllegalStateException("Apple JWKS 公钥类型无效");
            }
            return rsaPublicKey;
        } catch (Exception ex) {
            throw new IllegalStateException("Apple JWKS 公钥解析失败", ex);
        }
    }

    private void validateClaims(Claims claims) {
        String issuer = claims.getIssuer();
        if (!"https://appleid.apple.com".equals(issuer)) {
            throw new IllegalStateException("Apple id_token issuer 无效");
        }

        Set<String> audience = claims.getAudience();
        if (clientId.isBlank() || audience == null || !audience.contains(clientId)) {
            throw new IllegalStateException("Apple id_token audience 无效");
        }
    }

    private String resolveAudience(Claims claims) {
        Set<String> audience = claims.getAudience();
        if (audience == null || audience.isEmpty()) {
            return null;
        }
        return String.join(",", audience);
    }

    private Boolean parseBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text) || "1".equals(text);
        }
        return null;
    }
}
