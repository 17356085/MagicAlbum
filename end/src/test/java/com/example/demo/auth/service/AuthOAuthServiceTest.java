package com.example.demo.auth.service;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.GithubAccessTokenResponse;
import com.example.demo.auth.dto.GithubUserProfile;
import com.example.demo.auth.dto.AppleIdTokenClaims;
import com.example.demo.auth.dto.AppleTokenResponse;
import com.example.demo.auth.dto.GoogleAccessTokenResponse;
import com.example.demo.auth.dto.GoogleUserProfile;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.user.connected.UserConnectedAccountsService;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthOAuthServiceTest {

    @Test
    void shouldBuildGithubAuthorizeRedirect() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        String redirect = service.buildAuthorizeRedirect(OAuthProvider.github);

        assertTrue(redirect.startsWith("https://github.com/login/oauth/authorize"));
        assertTrue(redirect.contains("client_id=github-client-id"));
        assertTrue(redirect.contains("redirect_uri="));
        assertTrue(redirect.contains("scope=read%3Auser+user%3Aemail"));
        assertTrue(redirect.contains("state="));
    }

    @Test
    void shouldRejectAuthorizeWhenGithubClientIdMissing() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.buildAuthorizeRedirect(OAuthProvider.github)
        );

        assertEquals(503, ex.getStatusCode().value());
        assertEquals("GitHub OAuth clientId 未配置", ex.getReason());
    }

    @Test
    void shouldBuildGithubCallbackSuccessRedirectWithLocalToken() {
        UserRepository userRepository = mock(UserRepository.class);
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserConnectedAccountsService connectedAccountsService = new UserConnectedAccountsService();
        PasswordEncoder passwordEncoder = mockPasswordEncoder();
        JwtTokenProvider jwtTokenProvider = mockJwtTokenProvider();
        GithubOAuthClient githubOAuthClient = mockGithubClient();

        when(userRepository.findByEmail("stick@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername("stick_i")).thenReturn(false);
        when(userRepository.existsByEmail("stick@example.com")).thenReturn(false);
        when(userRepository.existsByPhone(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1001L);
            return user;
        });
        when(userProfileRepository.findById(1001L)).thenReturn(Optional.empty());
        when(jwtTokenProvider.generateToken(1001L, "stick_i")).thenReturn("local-jwt-token");

        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                githubOAuthClient,
                mockGoogleClient(),
                mockAppleClient(),
                userRepository,
                userProfileRepository,
                connectedAccountsService,
                passwordEncoder,
                jwtTokenProvider
        );

        String authorizeUrl = service.buildAuthorizeRedirect(OAuthProvider.github);
        String state = authorizeUrl.substring(authorizeUrl.indexOf("state=") + 6);

        String redirect = service.buildGithubCallbackRedirect("github-code", state, null);

        assertTrue(redirect.startsWith("http://localhost:5173/auth/oauth/callback?provider=github"));
        assertTrue(redirect.contains("status=success"));
        assertTrue(redirect.contains("accessToken=local-jwt-token"));
        assertTrue(redirect.contains("username=stick_i"));
        assertTrue(redirect.contains("userId=1001"));
    }

    @Test
    void shouldBuildGithubCallbackErrorRedirect() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        String authorizeUrl = service.buildAuthorizeRedirect(OAuthProvider.github);
        String state = authorizeUrl.substring(authorizeUrl.indexOf("state=") + 6);

        String redirect = service.buildGithubCallbackRedirect(null, state, "access_denied");

        assertTrue(redirect.contains("status=error"));
        assertTrue(redirect.contains("error=access_denied"));
    }

    @Test
    void shouldRejectInvalidStateOnCallback() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        String redirect = service.buildGithubCallbackRedirect("github-code", "invalid-state", null);

        assertTrue(redirect.startsWith("http://localhost:5173/auth/oauth/callback?provider=github"));
        assertTrue(redirect.contains("status=error"));
        assertTrue(redirect.contains("error="));
    }

    @Test
    void shouldBuildAppleAuthorizeRedirect() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        String redirect = service.buildAuthorizeRedirect(OAuthProvider.apple);

        assertTrue(redirect.startsWith("https://appleid.apple.com/auth/authorize"));
        assertTrue(redirect.contains("client_id=apple-services-id"));
        assertTrue(redirect.contains("redirect_uri="));
        assertTrue(redirect.contains("response_type=code"));
        assertTrue(redirect.contains("response_mode=query"));
        assertTrue(redirect.contains("scope=name+email"));
        assertTrue(redirect.contains("state="));
    }

    @Test
    void shouldBuildAppleCallbackSuccessRedirect() {
        UserRepository userRepository = mock(UserRepository.class);
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserConnectedAccountsService connectedAccountsService = new UserConnectedAccountsService();
        PasswordEncoder passwordEncoder = mockPasswordEncoder();
        JwtTokenProvider jwtTokenProvider = mockJwtTokenProvider();
        AppleOAuthClient appleOAuthClient = mockAppleClient();

        when(userRepository.findByEmail("apple@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername("apple")).thenReturn(false);
        when(userRepository.existsByEmail("apple@example.com")).thenReturn(false);
        when(userRepository.existsByPhone(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(3003L);
            return user;
        });
        when(userProfileRepository.findById(3003L)).thenReturn(Optional.empty());
        when(jwtTokenProvider.generateToken(3003L, "apple")).thenReturn("apple-local-jwt-token");

        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                appleOAuthClient,
                userRepository,
                userProfileRepository,
                connectedAccountsService,
                passwordEncoder,
                jwtTokenProvider
        );

        String authorizeUrl = service.buildAuthorizeRedirect(OAuthProvider.apple);
        String state = authorizeUrl.substring(authorizeUrl.indexOf("state=") + 6);

        String redirect = service.buildAppleCallbackRedirect("apple-code", state, null);

        assertTrue(redirect.startsWith("http://localhost:5173/auth/oauth/callback?provider=apple"));
        assertTrue(redirect.contains("status=success"));
        assertTrue(redirect.contains("accessToken=apple-local-jwt-token"));
        assertTrue(redirect.contains("username=apple"));
        assertTrue(redirect.contains("userId=3003"));
    }

    @Test
    void shouldBuildGoogleAuthorizeRedirect() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        String redirect = service.buildAuthorizeRedirect(OAuthProvider.google);

        assertTrue(redirect.startsWith("https://accounts.google.com/o/oauth2/v2/auth"));
        assertTrue(redirect.contains("client_id=google-client-id"));
        assertTrue(redirect.contains("redirect_uri="));
        assertTrue(redirect.contains("scope=openid+email+profile"));
        assertTrue(redirect.contains("response_type=code"));
        assertTrue(redirect.contains("state="));
    }

    @Test
    void shouldBuildGoogleCallbackSuccessRedirect() {
        UserRepository userRepository = mock(UserRepository.class);
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserConnectedAccountsService connectedAccountsService = new UserConnectedAccountsService();
        PasswordEncoder passwordEncoder = mockPasswordEncoder();
        JwtTokenProvider jwtTokenProvider = mockJwtTokenProvider();
        GoogleOAuthClient googleOAuthClient = mockGoogleClient();

        when(userRepository.findByEmail("google@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername("google")).thenReturn(false);
        when(userRepository.existsByEmail("google@example.com")).thenReturn(false);
        when(userRepository.existsByPhone(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(2002L);
            return user;
        });
        when(userProfileRepository.findById(2002L)).thenReturn(Optional.empty());
        when(jwtTokenProvider.generateToken(2002L, "google")).thenReturn("google-local-jwt-token");

        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                googleOAuthClient,
                mockAppleClient(),
                userRepository,
                userProfileRepository,
                connectedAccountsService,
                passwordEncoder,
                jwtTokenProvider
        );

        String authorizeUrl = service.buildAuthorizeRedirect(OAuthProvider.google);
        String state = authorizeUrl.substring(authorizeUrl.indexOf("state=") + 6);

        String redirect = service.buildGoogleCallbackRedirect("google-code", state, null);

        assertTrue(redirect.startsWith("http://localhost:5173/auth/oauth/callback?provider=google"));
        assertTrue(redirect.contains("status=success"));
        assertTrue(redirect.contains("accessToken=google-local-jwt-token"));
        assertTrue(redirect.contains("username=google"));
        assertTrue(redirect.contains("userId=2002"));
    }

    @Test
    void shouldBuildWechatAuthorizeRedirect() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        String redirect = service.buildAuthorizeRedirect(OAuthProvider.wechat);

        assertTrue(redirect.startsWith("https://open.weixin.qq.com/connect/qrconnect"));
        assertTrue(redirect.contains("appid=wechat-app-id"));
        assertTrue(redirect.contains("redirect_uri="));
        assertTrue(redirect.contains("scope=snsapi_login"));
        assertTrue(redirect.contains("state="));
        assertTrue(redirect.endsWith("#wechat_redirect"));
    }

    @Test
    void shouldBuildWechatCallbackPendingRedirect() {
        AuthOAuthService service = AuthOAuthService.forTest(
                "http://localhost:5173/auth/oauth/callback",
                300,
                "github-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/github",
                "read:user user:email",
                "google-client-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/google",
                "openid email profile",
                "apple-services-id",
                "http://localhost:8080/api/v1/auth/oauth/callback/apple",
                "name email",
                "wechat-app-id",
                "https://example.test/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mockGithubClient(),
                mockGoogleClient(),
                mockAppleClient(),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                new UserConnectedAccountsService(),
                mockPasswordEncoder(),
                mockJwtTokenProvider()
        );

        String authorizeUrl = service.buildAuthorizeRedirect(OAuthProvider.wechat);
        String state = authorizeUrl.substring(authorizeUrl.indexOf("state=") + 6, authorizeUrl.indexOf("#wechat_redirect"));

        String redirect = service.buildWechatCallbackRedirect("wechat-code", state, null);

        assertTrue(redirect.startsWith("http://localhost:5173/auth/oauth/callback?provider=wechat"));
        assertTrue(redirect.contains("status=error"));
        assertTrue(redirect.contains("error="));
    }

    private GithubOAuthClient mockGithubClient() {
        GithubOAuthClient client = mock(GithubOAuthClient.class);

        GithubAccessTokenResponse tokenResponse = new GithubAccessTokenResponse();
        tokenResponse.setAccessToken("github-access-token");
        when(client.exchangeCode("github-code")).thenReturn(tokenResponse);

        GithubUserProfile profile = new GithubUserProfile();
        profile.setId(9001L);
        profile.setLogin("stick_i");
        profile.setName("Stick I");
        profile.setEmail("stick@example.com");
        profile.setAvatarUrl("https://avatars.githubusercontent.com/u/9001");
        when(client.fetchUserProfile("github-access-token")).thenReturn(profile);

        return client;
    }

    private GoogleOAuthClient mockGoogleClient() {
        GoogleOAuthClient client = mock(GoogleOAuthClient.class);

        GoogleAccessTokenResponse tokenResponse = new GoogleAccessTokenResponse();
        tokenResponse.setAccessToken("google-access-token");
        when(client.exchangeCode("google-code")).thenReturn(tokenResponse);

        GoogleUserProfile profile = new GoogleUserProfile();
        profile.setSub("google-sub-2002");
        profile.setName("Google User");
        profile.setEmail("google@example.com");
        profile.setPicture("https://lh3.googleusercontent.com/a/example");
        when(client.fetchUserProfile("google-access-token")).thenReturn(profile);

        return client;
    }

    private AppleOAuthClient mockAppleClient() {
        AppleOAuthClient client = mock(AppleOAuthClient.class);

        AppleTokenResponse tokenResponse = new AppleTokenResponse();
        tokenResponse.setAccessToken("apple-access-token");
        tokenResponse.setIdToken("apple-id-token");
        when(client.exchangeCode("apple-code")).thenReturn(tokenResponse);

        AppleIdTokenClaims claims = new AppleIdTokenClaims();
        claims.setSub("apple-sub-3003");
        claims.setEmail("apple@example.com");
        claims.setEmailVerified(true);
        when(client.parseIdToken("apple-id-token")).thenReturn(claims);

        return client;
    }

    private PasswordEncoder mockPasswordEncoder() {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any(String.class))).thenReturn("encoded-oauth-password");
        return passwordEncoder;
    }

    private JwtTokenProvider mockJwtTokenProvider() {
        return mock(JwtTokenProvider.class);
    }
}
