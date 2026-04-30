package com.example.demo.auth.service.apple;

import com.example.demo.auth.dto.apple.AppleIdTokenClaims;
import com.example.demo.auth.dto.apple.AppleTokenResponse;

public interface AppleOAuthClient {
    AppleTokenResponse exchangeCode(String code);

    AppleIdTokenClaims parseIdToken(String idToken);
}
