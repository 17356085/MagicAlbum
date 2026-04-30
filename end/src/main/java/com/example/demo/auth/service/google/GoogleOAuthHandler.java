package com.example.demo.auth.service.google;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.dto.google.GoogleAccessTokenResponse;
import com.example.demo.auth.dto.google.GoogleUserProfile;
import com.example.demo.auth.service.oauth.common.AuthOAuthService;
import com.example.demo.auth.service.oauth.common.OAuthProviderHandler;
import com.example.demo.auth.service.oauth.common.OAuthUserProvisioningService;
import com.example.demo.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class GoogleOAuthHandler implements OAuthProviderHandler {
    private final AuthOAuthService authOAuthService;
    private final GoogleOAuthClient googleOAuthClient;
    private final OAuthUserProvisioningService oauthUserProvisioningService;
    private final JwtTokenProvider jwtTokenProvider;

    public GoogleOAuthHandler(
            AuthOAuthService authOAuthService,
            GoogleOAuthClient googleOAuthClient,
            OAuthUserProvisioningService oauthUserProvisioningService,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.authOAuthService = authOAuthService;
        this.googleOAuthClient = googleOAuthClient;
        this.oauthUserProvisioningService = oauthUserProvisioningService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.google;
    }

    @Override
    public String buildAuthorizeRedirect() {
        return authOAuthService.buildAuthorizeRedirect(OAuthProvider.google);
    }

    @Override
    public String handleCallback(String code, String state, String error) {
        if (!authOAuthService.consumeStateSafely(OAuthProvider.google, state)) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.google, "OAuth state 无效或已过期");
        }
        if (error != null && !error.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.google, error);
        }
        if (code == null || code.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.google, "缺少 Google 授权 code");
        }

        GoogleAccessTokenResponse tokenResponse;
        GoogleUserProfile googleUser;
        try {
            tokenResponse = googleOAuthClient.exchangeCode(code);
            googleUser = googleOAuthClient.fetchUserProfile(tokenResponse.getAccessToken());
        } catch (Exception ex) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.google, "Google OAuth 登录失败，请检查客户端密钥、回调地址和网络连通性");
        }

        User user = oauthUserProvisioningService.resolveOrCreateGoogleUser(googleUser);
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        return authOAuthService.buildSuccessRedirect(OAuthProvider.google, user, accessToken);
    }
}
