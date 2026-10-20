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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("owned-jwt", service.exchangeAuthorizationCode(code,
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").getAccessToken());
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
