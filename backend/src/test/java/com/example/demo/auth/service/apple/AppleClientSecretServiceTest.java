package com.example.demo.auth.service.apple;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppleClientSecretServiceTest {

    @Test
    void shouldGenerateAppleClientSecretJwt() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair keyPair = generator.generateKeyPair();
        String pem = toPem(keyPair);

        AppleClientSecretService service = AppleClientSecretService.forTest(
                "com.example.service",
                "TEAM123456",
                "KEY123456",
                pem,
                3600
        );

        String token = service.generateClientSecret();
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3);

        var claims = Jwts.parser()
                .verifyWith((ECPublicKey) keyPair.getPublic())
                .build()
                .parseSignedClaims(token);

        assertEquals("TEAM123456", claims.getPayload().getIssuer());
        assertEquals("com.example.service", claims.getPayload().getSubject());
        assertEquals("https://appleid.apple.com", claims.getPayload().getAudience().iterator().next());
        assertEquals("KEY123456", claims.getHeader().getKeyId());
        assertTrue(claims.getPayload().getExpiration().toInstant().isAfter(Instant.now()));
    }

    @Test
    void shouldRejectWhenAppleClientSecretConfigMissing() {
        AppleClientSecretService service = AppleClientSecretService.forTest(
                "",
                "TEAM123456",
                "KEY123456",
                "invalid",
                3600
        );

        assertThrows(IllegalStateException.class, service::generateClientSecret);
    }

    private String toPem(KeyPair keyPair) {
        String encoded = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }
}
