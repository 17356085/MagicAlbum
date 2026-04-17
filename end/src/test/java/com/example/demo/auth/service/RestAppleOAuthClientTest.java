package com.example.demo.auth.service;

import com.example.demo.auth.dto.AppleIdTokenClaims;
import com.example.demo.auth.dto.AppleJwk;
import com.example.demo.auth.dto.AppleJwkSetResponse;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class RestAppleOAuthClientTest {

    @Test
    void shouldVerifyAppleIdTokenWithJwks() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        AppleJwk jwk = new AppleJwk();
        jwk.setKid("apple-key-1");
        jwk.setAlg("RS256");
        jwk.setKty("RSA");
        jwk.setUse("sig");
        jwk.setN(Base64.getUrlEncoder().withoutPadding().encodeToString(toUnsignedBytes(publicKey.getModulus())));
        jwk.setE(Base64.getUrlEncoder().withoutPadding().encodeToString(toUnsignedBytes(publicKey.getPublicExponent())));

        AppleJwkSetResponse jwkSet = new AppleJwkSetResponse();
        jwkSet.getKeys().add(jwk);

        AppleJwksClient jwksClient = () -> jwkSet;
        AppleClientSecretService secretService = AppleClientSecretService.forTest("", "", "", "", 300);
        RestAppleOAuthClient client = new RestAppleOAuthClient(
                secretService,
                jwksClient,
                "com.example.web",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple"
        );

        String token = Jwts.builder()
                .header().keyId("apple-key-1").and()
                .issuer("https://appleid.apple.com")
                .audience().add("com.example.web").and()
                .subject("apple-sub-1")
                .claim("email", "apple@example.com")
                .claim("email_verified", true)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        AppleIdTokenClaims claims = client.parseIdToken(token);

        assertEquals("apple-sub-1", claims.getSub());
        assertEquals("apple@example.com", claims.getEmail());
        assertEquals(true, claims.getEmailVerified());
    }

    @Test
    void shouldRejectAppleIdTokenWhenAudienceInvalid() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        AppleJwk jwk = new AppleJwk();
        jwk.setKid("apple-key-2");
        jwk.setAlg("RS256");
        jwk.setKty("RSA");
        jwk.setUse("sig");
        jwk.setN(Base64.getUrlEncoder().withoutPadding().encodeToString(toUnsignedBytes(publicKey.getModulus())));
        jwk.setE(Base64.getUrlEncoder().withoutPadding().encodeToString(toUnsignedBytes(publicKey.getPublicExponent())));

        AppleJwkSetResponse jwkSet = new AppleJwkSetResponse();
        jwkSet.getKeys().add(jwk);

        RestAppleOAuthClient client = new RestAppleOAuthClient(
                AppleClientSecretService.forTest("", "", "", "", 300),
                () -> jwkSet,
                "com.example.web",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple"
        );

        String token = Jwts.builder()
                .header().keyId("apple-key-2").and()
                .issuer("https://appleid.apple.com")
                .audience().add("other.client.id").and()
                .subject("apple-sub-2")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        assertThrows(IllegalStateException.class, () -> client.parseIdToken(token));
    }

    @Test
    void shouldRejectAppleIdTokenWhenJwkMissing() {
        RestAppleOAuthClient client = new RestAppleOAuthClient(
                AppleClientSecretService.forTest("", "", "", "", 300),
                AppleJwkSetResponse::new,
                "com.example.web",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple"
        );

        assertThrows(IllegalStateException.class, () -> client.parseIdToken("a.b.c"));
    }

    private byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] unsigned = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, unsigned, 0, unsigned.length);
            return unsigned;
        }
        return bytes;
    }
}
