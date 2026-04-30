package com.example.demo.auth.service.apple;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.apple.AppleIdTokenClaims;
import com.example.demo.auth.dto.apple.AppleTokenResponse;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.service.oauth.common.AuthOAuthService;
import com.example.demo.auth.service.oauth.common.OAuthProviderHandler;
import com.example.demo.auth.service.oauth.common.OAuthUserProvisioningService;
import com.example.demo.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class AppleOAuthHandler implements OAuthProviderHandler {
    private final AuthOAuthService authOAuthService;
    private final AppleOAuthClient appleOAuthClient;
    private final OAuthUserProvisioningService oauthUserProvisioningService;
    private final JwtTokenProvider jwtTokenProvider;

    public AppleOAuthHandler(
            AuthOAuthService authOAuthService,
            AppleOAuthClient appleOAuthClient,
            OAuthUserProvisioningService oauthUserProvisioningService,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.authOAuthService = authOAuthService;
        this.appleOAuthClient = appleOAuthClient;
        this.oauthUserProvisioningService = oauthUserProvisioningService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.apple;
    }

    @Override
    public String buildAuthorizeRedirect() {
        return authOAuthService.buildAuthorizeRedirect(OAuthProvider.apple);
    }

    @Override
    public String handleCallback(String code, String state, String error) {
        if (!authOAuthService.consumeStateSafely(OAuthProvider.apple, state)) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.apple, "OAuth state 无效或已过期");
        }
        if (error != null && !error.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.apple, error);
        }
        if (code == null || code.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.apple, "缺少 Apple 授权 code");
        }

        AppleTokenResponse tokenResponse;
        AppleIdTokenClaims appleUser;
        try {
            tokenResponse = appleOAuthClient.exchangeCode(code);
            appleUser = appleOAuthClient.parseIdToken(tokenResponse.getIdToken());
        } catch (Exception ex) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.apple, "Apple 登录失败，请检查 client secret、回调地址和开发者配置");
        }

        User user = oauthUserProvisioningService.resolveOrCreateAppleUser(appleUser);
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        return authOAuthService.buildSuccessRedirect(OAuthProvider.apple, user, accessToken);
    }
}
