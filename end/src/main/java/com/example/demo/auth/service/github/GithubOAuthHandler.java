package com.example.demo.auth.service.github;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.dto.github.GithubAccessTokenResponse;
import com.example.demo.auth.dto.github.GithubUserProfile;
import com.example.demo.auth.service.oauth.common.AuthOAuthService;
import com.example.demo.auth.service.oauth.common.OAuthProviderHandler;
import com.example.demo.auth.service.oauth.common.OAuthUserProvisioningService;
import com.example.demo.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class GithubOAuthHandler implements OAuthProviderHandler {
    private final AuthOAuthService authOAuthService;
    private final GithubOAuthClient githubOAuthClient;
    private final OAuthUserProvisioningService oauthUserProvisioningService;
    private final JwtTokenProvider jwtTokenProvider;

    public GithubOAuthHandler(
            AuthOAuthService authOAuthService,
            GithubOAuthClient githubOAuthClient,
            OAuthUserProvisioningService oauthUserProvisioningService,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.authOAuthService = authOAuthService;
        this.githubOAuthClient = githubOAuthClient;
        this.oauthUserProvisioningService = oauthUserProvisioningService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.github;
    }

    @Override
    public String buildAuthorizeRedirect() {
        return authOAuthService.buildAuthorizeRedirect(OAuthProvider.github);
    }

    @Override
    public String handleCallback(String code, String state, String error) {
        if (!authOAuthService.consumeStateSafely(OAuthProvider.github, state)) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.github, "OAuth state 无效或已过期");
        }
        if (error != null && !error.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.github, error);
        }
        if (code == null || code.isBlank()) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.github, "缺少 GitHub OAuth code");
        }

        GithubAccessTokenResponse tokenResponse;
        GithubUserProfile githubUser;
        try {
            tokenResponse = githubOAuthClient.exchangeCode(code);
            githubUser = githubOAuthClient.fetchUserProfile(tokenResponse.getAccessToken());
        } catch (Exception ex) {
            return authOAuthService.buildProviderErrorRedirect(OAuthProvider.github, "GitHub OAuth 登录失败，请检查应用密钥和回调配置");
        }

        User user = oauthUserProvisioningService.resolveOrCreateGithubUser(githubUser);
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        return authOAuthService.buildSuccessRedirect(OAuthProvider.github, user, accessToken);
    }
}
