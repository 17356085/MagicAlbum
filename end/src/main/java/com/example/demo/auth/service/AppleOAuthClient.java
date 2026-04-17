package com.example.demo.auth.service;

import com.example.demo.auth.dto.AppleIdTokenClaims;
import com.example.demo.auth.dto.AppleTokenResponse;

public interface AppleOAuthClient {
    AppleTokenResponse exchangeCode(String code);

    AppleIdTokenClaims parseIdToken(String idToken);
}
