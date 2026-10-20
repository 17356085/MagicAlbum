package com.example.demo.auth.service.wechat;

import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.service.oauth.common.AuthOAuthService;
import com.example.demo.auth.service.oauth.common.OAuthProviderHandler;
import org.springframework.stereotype.Component;

@Component
public class WechatOAuthHandler implements OAuthProviderHandler {
    private final AuthOAuthService authOAuthService;

    public WechatOAuthHandler(AuthOAuthService authOAuthService) {
        this.authOAuthService = authOAuthService;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.wechat;
    }

    @Override
    public String buildAuthorizeRedirect(String codeChallenge) {
        return authOAuthService.buildAuthorizeRedirect(OAuthProvider.wechat, codeChallenge);
    }

    @Override
    public String handleCallback(String code, String state, String error) {
        String codeChallenge = authOAuthService.consumeStateProof(OAuthProvider.wechat, state);
        if (codeChallenge == null) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.wechat, "OAuth state 无效或已过期");
        }
        if (error != null && !error.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.wechat, error);
        }
        if (code == null || code.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.wechat, "缺少微信授权 code");
        }
        return authOAuthService.buildProviderErrorRedirect(OAuthProvider.wechat, "微信登录骨架已接入，需配置可访问域名后继续联调");
    }
}
