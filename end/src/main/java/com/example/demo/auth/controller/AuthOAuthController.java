package com.example.demo.auth.controller;

import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.service.AuthOAuthService;
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
    private final AuthOAuthService authOAuthService;

    public AuthOAuthController(AuthOAuthService authOAuthService) {
        this.authOAuthService = authOAuthService;
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam("provider") OAuthProvider provider) {
        String location = authOAuthService.buildAuthorizeRedirect(provider);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    @GetMapping("/callback/{provider}")
    public ResponseEntity<Void> callback(
            @PathVariable("provider") OAuthProvider provider,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error
    ) {
        String location;
        if (provider == OAuthProvider.github) {
            location = authOAuthService.buildGithubCallbackRedirect(code, state, error);
        } else if (provider == OAuthProvider.google) {
            location = authOAuthService.buildGoogleCallbackRedirect(code, state, error);
        } else if (provider == OAuthProvider.apple) {
            location = authOAuthService.buildAppleCallbackRedirect(code, state, error);
        } else if (provider == OAuthProvider.wechat) {
            location = authOAuthService.buildWechatCallbackRedirect(code, state, error);
        } else {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "当前 Provider 尚未接入 OAuth 回调骨架");
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }
}
