package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.OAuthProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuthLoginCoordinatorTest {

    @Test
    void shouldDispatchAuthorizeAndCallbackToMatchedHandler() {
        OAuthProviderHandler githubHandler = new StubHandler(
                OAuthProvider.github,
                "github-authorize",
                "github-callback"
        );
        OAuthProviderHandler googleHandler = new StubHandler(
                OAuthProvider.google,
                "google-authorize",
                "google-callback"
        );

        OAuthLoginCoordinator coordinator = new OAuthLoginCoordinator(List.of(githubHandler, googleHandler));

        assertEquals("github-authorize", coordinator.buildAuthorizeRedirect(OAuthProvider.github));
        assertEquals("google-callback", coordinator.handleCallback(OAuthProvider.google, "code", "state", null));
    }

    private record StubHandler(
            OAuthProvider provider,
            String authorizeResult,
            String callbackResult
    ) implements OAuthProviderHandler {

        @Override
        public String buildAuthorizeRedirect() {
            return authorizeResult;
        }

        @Override
        public String handleCallback(String code, String state, String error) {
            return callbackResult;
        }
    }
}
