package com.example.demo.auth.controller;

import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.auth.dto.AuthCodeFinishRequest;
import com.example.demo.auth.dto.AuthCodeStartRequest;
import com.example.demo.auth.dto.AuthCodeStartResponse;
import com.example.demo.auth.service.AuthService;
import com.example.demo.auth.service.otp.CognitoOtpAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CognitoOtpAuthService cognitoOtpAuthService;

    public AuthController(AuthService authService, CognitoOtpAuthService cognitoOtpAuthService) {
        this.authService = authService;
        this.cognitoOtpAuthService = cognitoOtpAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        LoginResponse resp = authService.login(req);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/code/start")
    public ResponseEntity<AuthCodeStartResponse> startCodeLogin(@Valid @RequestBody AuthCodeStartRequest req) {
        return ResponseEntity.ok(cognitoOtpAuthService.start(req));
    }

    @PostMapping("/code/finish")
    public ResponseEntity<LoginResponse> finishCodeLogin(@Valid @RequestBody AuthCodeFinishRequest req) {
        return ResponseEntity.ok(cognitoOtpAuthService.finish(req));
    }
}
