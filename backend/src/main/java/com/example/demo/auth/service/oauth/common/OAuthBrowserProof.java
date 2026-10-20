package com.example.demo.auth.service.oauth.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** S256 binding of the application's one-time login code to its initiating tab. */
public final class OAuthBrowserProof {
    private OAuthBrowserProof() { }

    public static String requireChallenge(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        return value;
    }

    public static String challenge(String verifier) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) {
            throw invalid();
        }
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth 登录校验信息无效，请重新发起登录");
    }
}
