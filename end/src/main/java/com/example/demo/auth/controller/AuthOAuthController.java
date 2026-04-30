package com.example.demo.auth.controller;

import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.service.oauth.common.OAuthLoginCoordinator;
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

    public AuthOAuthController(OAuthLoginCoordinator oauthLoginCoordinator) {
        this.oauthLoginCoordinator = oauthLoginCoordinator;
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam("provider") OAuthProvider provider) {
        String location = oauthLoginCoordinator.buildAuthorizeRedirect(provider);
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
        String location = oauthLoginCoordinator.handleCallback(provider, code, state, error);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }
}
