package com.example.demo.auth.service.otp;

import com.example.demo.auth.dto.TurnstileSiteVerifyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthVerifyServiceTest {

    @Test
    void shouldBypassVerifyWhenModeIsOff() {
        AuthVerifyService service = AuthVerifyService.forTest("off", "", token -> {
            throw new AssertionError("off mode should not call verify client");
        });
        assertDoesNotThrow(() -> service.verify(null, null, null, "login"));
    }

    @Test
    void shouldAcceptMockVerifyTicketWhenModeIsMock() {
        AuthVerifyService service = AuthVerifyService.forTest("mock", "", token -> {
            throw new AssertionError("mock mode should not call verify client");
        });
        assertDoesNotThrow(() -> service.verify("mock_verify_123456", "mock-manual", "login", "login"));
    }

    @Test
    void shouldRejectSceneMismatchWhenModeIsMock() {
        AuthVerifyService service = AuthVerifyService.forTest("mock", "", token -> null);
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.verify("mock_verify_123456", "mock-manual", "register", "login")
        );
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("验证场景不匹配", ex.getReason());
    }

    @Test
    void shouldRejectMissingVerifyPayloadWhenModeIsMock() {
        AuthVerifyService service = AuthVerifyService.forTest("mock", "", token -> null);
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.verify("", "", "login", "login")
        );
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("请先完成人工验证", ex.getReason());
    }

    @Test
    void shouldRejectUnsupportedProviderWhenModeIsMock() {
        AuthVerifyService service = AuthVerifyService.forTest("mock", "", token -> null);
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.verify("mock_verify_123456", "third-party", "login", "login")
        );
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("验证提供方不受支持", ex.getReason());
    }

    @Test
    void shouldAcceptTurnstileTicketWhenVerificationSucceeds() {
        AuthVerifyService service = AuthVerifyService.forTest("turnstile", "localhost", token -> {
            TurnstileSiteVerifyResponse response = new TurnstileSiteVerifyResponse();
            response.setSuccess(true);
            response.setHostname("localhost");
            response.setAction("login");
            return response;
        });

        assertDoesNotThrow(() -> service.verify("turnstile-token", "turnstile", "login", "login"));
    }

    @Test
    void shouldRejectTurnstileWhenHostnameDoesNotMatch() {
        AuthVerifyService service = AuthVerifyService.forTest("turnstile", "localhost", token -> {
            TurnstileSiteVerifyResponse response = new TurnstileSiteVerifyResponse();
            response.setSuccess(true);
            response.setHostname("evil.example.com");
            response.setAction("login");
            return response;
        });

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.verify("turnstile-token", "turnstile", "login", "login")
        );
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("验证票据无效", ex.getReason());
    }

    @Test
    void shouldRejectTurnstileWhenActionDoesNotMatch() {
        AuthVerifyService service = AuthVerifyService.forTest("turnstile", "localhost", token -> {
            TurnstileSiteVerifyResponse response = new TurnstileSiteVerifyResponse();
            response.setSuccess(true);
            response.setHostname("localhost");
            response.setAction("register");
            return response;
        });

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.verify("turnstile-token", "turnstile", "login", "login")
        );
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("验证场景不匹配", ex.getReason());
    }

    @Test
    void shouldRejectExpiredTurnstileToken() {
        AuthVerifyService service = AuthVerifyService.forTest("turnstile", "localhost", token -> {
            TurnstileSiteVerifyResponse response = new TurnstileSiteVerifyResponse();
            response.setSuccess(false);
            response.setErrorCodes(List.of("timeout-or-duplicate"));
            return response;
        });

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.verify("turnstile-token", "turnstile", "login", "login")
        );
        assertEquals(400, ex.getStatusCode().value());
        assertEquals("验证票据已过期", ex.getReason());
    }

    @Test
    void shouldMapTurnstileServiceFailureToUnavailable() {
        AuthVerifyService service = AuthVerifyService.forTest("turnstile", "localhost", token -> {
            throw new IllegalStateException("network down");
        });

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.verify("turnstile-token", "turnstile", "login", "login")
        );
        assertEquals(503, ex.getStatusCode().value());
        assertEquals("人工验证服务暂不可用", ex.getReason());
    }
}
