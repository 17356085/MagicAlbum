package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.service.apple.AppleOAuthClient;
import com.example.demo.auth.service.github.GithubOAuthClient;
import com.example.demo.auth.service.google.GoogleOAuthClient;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthOAuthCodeExchangeTest {

    @Test
    void wrongBrowserCannotExchangeOrConsumeTheInitiatingBrowsersCode() {
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        when(tokens.generateToken(77L, "oauth-user")).thenReturn("owned-jwt");
        AuthOAuthService service = service(tokens);
        User user = new User(); user.setId(77L); user.setUsername("oauth-user");
        String code = queryParameter(service.buildSuccessRedirect(OAuthProvider.github, user,
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"), "code");
        ResponseStatusException wrong = assertThrows(ResponseStatusException.class,
                () -> service.exchangeAuthorizationCode(code, "a".repeat(43)));
        assertEquals(400, wrong.getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(tokens);
        LoginResponse response = service.exchangeAuthorizationCode(code, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        assertEquals("owned-jwt", response.getAccessToken());
        assertNull(response.getUser().getEmail());
        assertNull(response.getUser().getPhone());
    }

    @Test
    void shouldExchangeAuthorizationCodeOnceWithoutPuttingJwtInRedirect() {
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        when(jwtTokenProvider.generateToken(77L, "oauth-user")).thenReturn("server-issued-jwt");
        AuthOAuthService service = service(jwtTokenProvider);

        User user = new User();
        user.setId(77L);
        user.setUsername("oauth-user");
        user.setEmail("oauth@example.com");

        String redirect = service.buildSuccessRedirect(OAuthProvider.github, user, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assertFalse(redirect.contains("accessToken="));
        assertFalse(redirect.contains("server-issued-jwt"));

        String code = queryParameter(redirect, "code");
        LoginResponse response = service.exchangeAuthorizationCode(code, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        assertEquals("server-issued-jwt", response.getAccessToken());
        assertEquals(77L, response.getUser().getId());
        assertNull(response.getUser().getPhone());

        ResponseStatusException replay = assertThrows(
                ResponseStatusException.class,
                () -> service.exchangeAuthorizationCode(code, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        );
        assertEquals(400, replay.getStatusCode().value());
    }

    @Test
    void shouldRejectMissingAuthorizationCode() {
        AuthOAuthService service = service(mock(JwtTokenProvider.class));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.exchangeAuthorizationCode(" ", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        );
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void inMemoryExchangePreservesAnExistingPhone() {
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        AuthOAuthService service = service(tokens);
        User user = new User();
        user.setId(77L);
        user.setUsername("oauth-user");
        user.setPhone("13800138000");
        user.setEmail("existing@example.test");
        String code = queryParameter(service.buildSuccessRedirect(OAuthProvider.github, user,
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"), "code");

        LoginResponse response = service.exchangeAuthorizationCode(code, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

        assertEquals("13800138000", response.getUser().getPhone());
        assertEquals("existing@example.test", response.getUser().getEmail());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisExchangeReturnsTheCurrentUsersUnassignedPhone() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        UserRepository users = mock(UserRepository.class);
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        User user = new User();
        user.setId(77L);
        user.setUsername("oauth-user");
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList(), anyString(), anyString()))
                .thenReturn("77");
        when(users.findById(77L)).thenReturn(Optional.of(user));
        when(tokens.generateToken(77L, "oauth-user")).thenReturn("owned-jwt");
        AuthOAuthService service = new AuthOAuthService("http://localhost:5173/auth/oauth/callback",
                "github", "https://example.test/callback", "read:user",
                "google", "https://example.test/callback", "openid",
                "apple", "https://example.test/callback", "email",
                "wechat", "https://example.test/callback", "snsapi_login",
                OAuthStateService.forTest(300), tokens, users, provider, 90);

        LoginResponse response = service.exchangeAuthorizationCode("test-code", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

        assertEquals("owned-jwt", response.getAccessToken());
        assertNull(response.getUser().getPhone());
        assertNull(response.getUser().getEmail());
    }

    private AuthOAuthService service(JwtTokenProvider jwtTokenProvider) {
        return AuthOAuthService.forTest(
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
                "http://localhost:8080/api/v1/auth/oauth/callback/wechat",
                "snsapi_login",
                mock(GithubOAuthClient.class),
                mock(GoogleOAuthClient.class),
                mock(AppleOAuthClient.class),
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                mock(ConnectedAccountsService.class),
                mock(PasswordEncoder.class),
                jwtTokenProvider
        );
    }

    private String queryParameter(String redirect, String name) {
        String query = URI.create(redirect).getRawQuery();
        for (String part : query.split("&")) {
            if (part.startsWith(name + "=")) {
                return URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("missing query parameter: " + name);
    }
}
