package com.example.demo.auth.service;

import com.example.demo.auth.dto.TurnstileSiteVerifyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class AuthVerifyService {
    private static final Set<String> SUPPORTED_SCENES = Set.of("login", "register");
    private static final String MOCK_PROVIDER = "mock-manual";
    private static final String MOCK_TOKEN_PREFIX = "mock_verify_";
    private static final String TURNSTILE_PROVIDER = "turnstile";

    private final String verifyMode;
    private final String expectedHostname;
    private final TurnstileVerifyClient turnstileVerifyClient;

    @Autowired
    public AuthVerifyService(
            @Value("${app.auth.verify.mode:off}") String verifyMode,
            @Value("${app.auth.verify.turnstile.expected-hostname:}") String expectedHostname,
            TurnstileVerifyClient turnstileVerifyClient
    ) {
        this.verifyMode = verifyMode == null ? "off" : verifyMode.trim().toLowerCase();
        this.expectedHostname = expectedHostname == null ? "" : expectedHostname.trim();
        this.turnstileVerifyClient = turnstileVerifyClient;
    }

    static AuthVerifyService forTest(String verifyMode, String expectedHostname, TurnstileVerifyClient turnstileVerifyClient) {
        return new AuthVerifyService(verifyMode, expectedHostname, turnstileVerifyClient);
    }

    public void verify(String verifyToken, String verifyProvider, String verifyScene, String expectedScene) {
        if ("off".equals(verifyMode)) {
            return;
        }

        validateScene(verifyScene, expectedScene);
        validatePresence(verifyToken, verifyProvider);

        if ("mock".equals(verifyMode)) {
            validateMockTicket(verifyToken, verifyProvider);
            return;
        }

        if ("turnstile".equals(verifyMode)) {
            validateTurnstileTicket(verifyToken, verifyProvider, expectedScene);
            return;
        }

        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "人工验证配置无效");
    }

    private void validateScene(String verifyScene, String expectedScene) {
        if (isBlank(verifyScene)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少验证场景");
        }
        if (!SUPPORTED_SCENES.contains(verifyScene)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证场景不支持");
        }
        if (!verifyScene.equals(expectedScene)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证场景不匹配");
        }
    }

    private void validatePresence(String verifyToken, String verifyProvider) {
        if (isBlank(verifyToken) || isBlank(verifyProvider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成人工验证");
        }
    }

    private void validateMockTicket(String verifyToken, String verifyProvider) {
        if (!MOCK_PROVIDER.equals(verifyProvider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证提供方不受支持");
        }
        if (!verifyToken.startsWith(MOCK_TOKEN_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证票据无效");
        }
    }

    private void validateTurnstileTicket(String verifyToken, String verifyProvider, String expectedScene) {
        if (!TURNSTILE_PROVIDER.equals(verifyProvider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证提供方不受支持");
        }

        final TurnstileSiteVerifyResponse response;
        try {
            response = turnstileVerifyClient.verify(verifyToken);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "人工验证服务暂不可用");
        }

        if (!response.isSuccess()) {
            throw buildTurnstileFailure(response.getErrorCodes());
        }

        if (!isBlank(expectedHostname) && !expectedHostname.equalsIgnoreCase(trimToEmpty(response.getHostname()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证票据无效");
        }

        if (!isBlank(response.getAction()) && !expectedScene.equals(response.getAction())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证场景不匹配");
        }
    }

    private ResponseStatusException buildTurnstileFailure(List<String> errorCodes) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证票据无效");
        }

        if (errorCodes.contains("timeout-or-duplicate")) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证票据已过期");
        }

        if (errorCodes.contains("internal-error")) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "人工验证服务暂不可用");
        }

        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证票据无效");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
