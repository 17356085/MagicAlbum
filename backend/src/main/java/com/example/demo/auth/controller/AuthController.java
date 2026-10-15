package com.example.demo.auth.controller;

import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.auth.dto.AuthCodeFinishRequest;
import com.example.demo.auth.dto.AuthCodeStartRequest;
import com.example.demo.auth.dto.AuthCodeStartResponse;
import com.example.demo.auth.service.AuthService;
import com.example.demo.auth.service.otp.CognitoOtpAuthService;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.RequestIdentity;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CognitoOtpAuthService cognitoOtpAuthService;
    private final DistributedRateLimiter rateLimiter;

    public AuthController(AuthService authService,
                          CognitoOtpAuthService cognitoOtpAuthService,
                          DistributedRateLimiter rateLimiter) {
        this.authService = authService;
        this.cognitoOtpAuthService = cognitoOtpAuthService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                               HttpServletRequest httpRequest) {
        String account = !isBlank(req.getEmail()) ? req.getEmail().trim().toLowerCase()
                : (!isBlank(req.getPhone()) ? req.getPhone().trim() : "missing");
        rateLimiter.enforce("login-ip", RequestIdentity.clientIp(httpRequest), 60, 30);
        rateLimiter.enforce("login-account", account, 600, 12);
        rateLimiter.enforce("login-device", RequestIdentity.device(httpRequest), 600, 20);
        LoginResponse resp = authService.login(req);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/code/start")
    public ResponseEntity<AuthCodeStartResponse> startCodeLogin(@Valid @RequestBody AuthCodeStartRequest req,
                                                                HttpServletRequest httpRequest) {
        String address = isBlank(req.getAddress()) ? "missing" : req.getAddress().trim().toLowerCase();
        rateLimiter.enforce("otp-start-ip", RequestIdentity.clientIp(httpRequest), 60, 20);
        rateLimiter.enforce("otp-start-address", address, 300, 5);
        rateLimiter.enforce("otp-start-device", RequestIdentity.device(httpRequest), 300, 10);
        return ResponseEntity.ok(cognitoOtpAuthService.start(req));
    }

    @PostMapping("/code/finish")
    public ResponseEntity<LoginResponse> finishCodeLogin(@Valid @RequestBody AuthCodeFinishRequest req,
                                                         HttpServletRequest httpRequest) {
        String address = isBlank(req.getAddress()) ? "missing" : req.getAddress().trim().toLowerCase();
        String session = isBlank(req.getSession()) ? "missing" : req.getSession().trim();
        rateLimiter.enforce("otp-verify-ip", RequestIdentity.clientIp(httpRequest), 300, 30);
        rateLimiter.enforce("otp-verify-address", address, 300, 10);
        rateLimiter.enforce("otp-verify-session", session, 300, 10);
        rateLimiter.enforce("otp-verify-device", RequestIdentity.device(httpRequest), 300, 20);
        return ResponseEntity.ok(cognitoOtpAuthService.finish(req));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
