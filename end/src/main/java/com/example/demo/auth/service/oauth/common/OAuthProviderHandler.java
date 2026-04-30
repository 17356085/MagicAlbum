package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.OAuthProvider;

public interface OAuthProviderHandler {
    OAuthProvider provider();

    String buildAuthorizeRedirect();

    String handleCallback(String code, String state, String error);
}
