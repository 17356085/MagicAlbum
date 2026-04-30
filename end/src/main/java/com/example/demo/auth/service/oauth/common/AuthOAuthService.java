package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.service.apple.AppleOAuthClient;
import com.example.demo.auth.service.github.GithubOAuthClient;
import com.example.demo.auth.service.google.GoogleOAuthClient;
import com.example.demo.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class AuthOAuthService {
    private final String frontendCallbackUrl;
    private final String githubClientId;
    private final String githubRedirectUri;
    private final String githubScope;
    private final String googleClientId;
    private final String googleRedirectUri;
    private final String googleScope;
    private final String appleClientId;
    private final String appleRedirectUri;
    private final String appleScope;
    private final String wechatAppId;
    private final String wechatRedirectUri;
    private final String wechatScope;
    private final OAuthStateService oauthStateService;

    public AuthOAuthService(
            @Value("${app.auth.oauth.frontend-callback-url:http://localhost:5173/auth/oauth/callback}") String frontendCallbackUrl,
            @Value("${app.auth.oauth.github.client-id:}") String githubClientId,
            @Value("${app.auth.oauth.github.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/github}") String githubRedirectUri,
            @Value("${app.auth.oauth.github.scope:read:user user:email}") String githubScope,
            @Value("${app.auth.oauth.google.client-id:}") String googleClientId,
            @Value("${app.auth.oauth.google.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/google}") String googleRedirectUri,
            @Value("${app.auth.oauth.google.scope:openid email profile}") String googleScope,
            @Value("${app.auth.oauth.apple.client-id:}") String appleClientId,
            @Value("${app.auth.oauth.apple.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/apple}") String appleRedirectUri,
            @Value("${app.auth.oauth.apple.scope:name email}") String appleScope,
            @Value("${app.auth.oauth.wechat.app-id:}") String wechatAppId,
            @Value("${app.auth.oauth.wechat.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/wechat}") String wechatRedirectUri,
            @Value("${app.auth.oauth.wechat.scope:snsapi_login}") String wechatScope,
            OAuthStateService oauthStateService
    ) {
        this.frontendCallbackUrl = trimToEmpty(frontendCallbackUrl);
        this.githubClientId = trimToEmpty(githubClientId);
        this.githubRedirectUri = trimToEmpty(githubRedirectUri);
        this.githubScope = trimToEmpty(githubScope);
        this.googleClientId = trimToEmpty(googleClientId);
        this.googleRedirectUri = trimToEmpty(googleRedirectUri);
        this.googleScope = trimToEmpty(googleScope);
        this.appleClientId = trimToEmpty(appleClientId);
        this.appleRedirectUri = trimToEmpty(appleRedirectUri);
        this.appleScope = trimToEmpty(appleScope);
        this.wechatAppId = trimToEmpty(wechatAppId);
        this.wechatRedirectUri = trimToEmpty(wechatRedirectUri);
        this.wechatScope = trimToEmpty(wechatScope);
        this.oauthStateService = oauthStateService;
    }

    static AuthOAuthService forTest(
            String frontendCallbackUrl,
            long stateTtlSeconds,
            String githubClientId,
            String githubRedirectUri,
            String githubScope,
            String googleClientId,
            String googleRedirectUri,
            String googleScope,
            String appleClientId,
            String appleRedirectUri,
            String appleScope,
            String wechatAppId,
            String wechatRedirectUri,
            String wechatScope,
            GithubOAuthClient githubOAuthClient,
            GoogleOAuthClient googleOAuthClient,
            AppleOAuthClient appleOAuthClient,
            com.example.demo.user.repo.UserRepository userRepository,
            com.example.demo.user.repo.UserProfileRepository userProfileRepository,
            com.example.demo.user.connected.service.ConnectedAccountsService userConnectedAccountsService,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        return new AuthOAuthService(
                frontendCallbackUrl,
                githubClientId,
                githubRedirectUri,
                githubScope,
                googleClientId,
                googleRedirectUri,
                googleScope,
                appleClientId,
                appleRedirectUri,
                appleScope,
                wechatAppId,
                wechatRedirectUri,
                wechatScope,
                OAuthStateService.forTest(stateTtlSeconds)
        );
    }

    public String buildAuthorizeRedirect(OAuthProvider provider) {
        if (provider == OAuthProvider.github) {
            return buildGithubAuthorizeRedirect();
        }
        if (provider == OAuthProvider.google) {
            return buildGoogleAuthorizeRedirect();
        }
        if (provider == OAuthProvider.apple) {
            return buildAppleAuthorizeRedirect();
        }
        if (provider == OAuthProvider.wechat) {
            return buildWechatAuthorizeRedirect();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前 Provider 尚未接入 OAuth 骨架");
    }

    String buildGithubAuthorizeRedirect() {
        String state = oauthStateService.issue(OAuthProvider.github);
        if (githubClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth clientId 未配置");
        }
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + encode(githubClientId)
                + "&redirect_uri=" + encode(githubRedirectUri)
                + "&scope=" + encode(githubScope)
                + "&state=" + encode(state);
    }

    String buildGoogleAuthorizeRedirect() {
        String state = oauthStateService.issue(OAuthProvider.google);
        if (googleClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google OAuth clientId 未配置");
        }
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + encode(googleClientId)
                + "&redirect_uri=" + encode(googleRedirectUri)
                + "&response_type=code"
                + "&scope=" + encode(googleScope.isBlank() ? "openid email profile" : googleScope)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + encode(state);
    }

    String buildAppleAuthorizeRedirect() {
        String state = oauthStateService.issue(OAuthProvider.apple);
        if (appleClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apple OAuth clientId 未配置");
        }
        return "https://appleid.apple.com/auth/authorize"
                + "?client_id=" + encode(appleClientId)
                + "&redirect_uri=" + encode(appleRedirectUri)
                + "&response_type=code"
                + "&response_mode=query"
                + "&scope=" + encode(appleScope.isBlank() ? "name email" : appleScope)
                + "&state=" + encode(state);
    }

    String buildWechatAuthorizeRedirect() {
        String state = oauthStateService.issue(OAuthProvider.wechat);
        if (wechatAppId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录 AppID 未配置");
        }
        return "https://open.weixin.qq.com/connect/qrconnect"
                + "?appid=" + encode(wechatAppId)
                + "&redirect_uri=" + encode(wechatRedirectUri)
                + "&response_type=code"
                + "&scope=" + encode(wechatScope.isBlank() ? "snsapi_login" : wechatScope)
                + "&state=" + encode(state)
                + "#wechat_redirect";
    }

    public boolean consumeStateSafely(OAuthProvider provider, String state) {
        return oauthStateService.consumeSafely(provider, state);
    }

    public String buildSuccessRedirect(OAuthProvider provider, User user, String accessToken) {
        String base = resolveFrontendCallbackBase();
        return new StringBuilder(base)
                .append(base.contains("?") ? "&" : "?")
                .append("provider=").append(encode(provider.name()))
                .append("&status=success")
                .append("&accessToken=").append(encode(accessToken))
                .append("&username=").append(encode(user.getUsername()))
                .append("&userId=").append(user.getId())
                .toString();
    }

    String encode(String value) {
        return URLEncoder.encode(trimToEmpty(value), StandardCharsets.UTF_8);
    }

    String resolveFrontendCallbackBase() {
        return frontendCallbackUrl.isBlank()
                ? "http://localhost:5173/auth/oauth/callback"
                : frontendCallbackUrl;
    }

    public String buildProviderErrorRedirect(OAuthProvider provider, String error) {
        String base = resolveFrontendCallbackBase();
        return new StringBuilder(base)
                .append(base.contains("?") ? "&" : "?")
                .append("provider=").append(encode(provider.name()))
                .append("&status=error")
                .append("&error=").append(encode(error))
                .toString();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
