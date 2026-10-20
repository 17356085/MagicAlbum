package com.example.demo.auth.controller;

import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.dto.OAuthCodeExchangeRequest;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.auth.service.oauth.common.AuthOAuthService;
import com.example.demo.auth.service.oauth.common.OAuthLoginCoordinator;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.RequestIdentity;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth/oauth")
public class AuthOAuthController {
    private final OAuthLoginCoordinator oauthLoginCoordinator;
    private final AuthOAuthService authOAuthService;
    private final DistributedRateLimiter rateLimiter;

    public AuthOAuthController(OAuthLoginCoordinator oauthLoginCoordinator,
                               AuthOAuthService authOAuthService,
                               DistributedRateLimiter rateLimiter) {
        this.oauthLoginCoordinator = oauthLoginCoordinator;
        this.authOAuthService = authOAuthService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam("provider") OAuthProvider provider,
                                          @RequestParam("code_challenge") String codeChallenge,
                                          @RequestParam(value = "code_challenge_method", defaultValue = "S256") String method,
                                          HttpServletRequest httpRequest) {
        if (!"S256".equals(method)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 S256 登录校验");
        }
        rateLimiter.enforce("oauth-start-ip", RequestIdentity.clientIp(httpRequest), 60, 20);
        rateLimiter.enforce("oauth-start-device", RequestIdentity.device(httpRequest), 300, 10);
        String location = oauthLoginCoordinator.buildAuthorizeRedirect(provider, codeChallenge);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    @GetMapping("/callback/{provider}")
    public ResponseEntity<Void> callback(
            @PathVariable("provider") OAuthProvider provider,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest httpRequest
    ) {
        rateLimiter.enforce("oauth-callback-ip", RequestIdentity.clientIp(httpRequest), 60, 30);
        String location = oauthLoginCoordinator.handleCallback(provider, code, state, error);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }

    @org.springframework.web.bind.annotation.PostMapping("/exchange")
    public ResponseEntity<LoginResponse> exchange(@Valid @org.springframework.web.bind.annotation.RequestBody OAuthCodeExchangeRequest request,
                                                  HttpServletRequest httpRequest) {
        rateLimiter.enforce("oauth-exchange-ip", RequestIdentity.clientIp(httpRequest), 60, 30);
        rateLimiter.enforce("oauth-exchange-device", RequestIdentity.device(httpRequest), 300, 20);
        return ResponseEntity.ok(authOAuthService.exchangeAuthorizationCode(request.getCode(), request.getCodeVerifier()));
    }
}
