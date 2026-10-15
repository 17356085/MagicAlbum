package com.example.demo.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "JwtTokenProviderTestSecret_0123456789";
    private static final String OTHER_SECRET = "JwtTokenProviderOtherSecret_0123456789";
    private static final String ISSUER = "MagicAlbum";

    private JwtTokenProvider provider;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secret", SECRET);
        ReflectionTestUtils.setField(provider, "issuer", ISSUER);
        ReflectionTestUtils.setField(provider, "ttlMinutes", 60L);
        provider.validateConfiguration();
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void generated_token_with_positive_subject_is_accepted() {
        String token = provider.generateToken(42L, "alice");

        assertThat(provider.getUserIdFromToken(token)).isEqualTo(42L);
    }

    @Test
    void signed_token_without_expiration_is_rejected() {
        String token = signedToken("42", ISSUER, null, signingKey);

        assertThat(provider.getUserIdFromToken(token)).isNull();
    }

    @Test
    void expired_token_is_rejected() {
        String token = signedToken("42", ISSUER, Date.from(Instant.now().minusSeconds(1)), signingKey);

        assertThat(provider.getUserIdFromToken(token)).isNull();
    }

    @Test
    void token_with_wrong_issuer_is_rejected() {
        String token = signedToken("42", "OtherIssuer", Date.from(Instant.now().plusSeconds(60)), signingKey);

        assertThat(provider.getUserIdFromToken(token)).isNull();
    }

    @Test
    void token_with_wrong_signature_is_rejected() {
        SecretKey otherKey = Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = signedToken("42", ISSUER, Date.from(Instant.now().plusSeconds(60)), otherKey);

        assertThat(provider.getUserIdFromToken(token)).isNull();
    }

    @Test
    void token_with_non_positive_subject_is_rejected() {
        String zeroSubject = signedToken("0", ISSUER, Date.from(Instant.now().plusSeconds(60)), signingKey);
        String negativeSubject = signedToken("-1", ISSUER, Date.from(Instant.now().plusSeconds(60)), signingKey);

        assertThat(provider.getUserIdFromToken(zeroSubject)).isNull();
        assertThat(provider.getUserIdFromToken(negativeSubject)).isNull();
    }

    private String signedToken(String subject, String tokenIssuer, Date expiration, SecretKey key) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuer(tokenIssuer)
                .issuedAt(Date.from(Instant.now()));
        if (expiration != null) {
            builder.expiration(expiration);
        }
        return builder.signWith(key).compact();
    }
}
