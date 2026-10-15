package com.example.demo.auth.controller;

import com.example.demo.auth.dto.QrLoginCancelRequest;
import com.example.demo.auth.dto.QrLoginConfirmRequest;
import com.example.demo.auth.dto.QrLoginCreateResponse;
import com.example.demo.auth.dto.QrLoginExchangeRequest;
import com.example.demo.auth.dto.QrLoginScanRequest;
import com.example.demo.auth.dto.QrLoginStatusRequest;
import com.example.demo.auth.dto.QrLoginStatusResponse;
import com.example.demo.auth.service.qr.AuthQrService;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.RequestIdentity;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth/qr")
public class AuthQrController {
    private final AuthQrService authQrService;
    private final DistributedRateLimiter rateLimiter;

    public AuthQrController(AuthQrService authQrService, DistributedRateLimiter rateLimiter) {
        this.authQrService = authQrService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<QrLoginCreateResponse> create(HttpServletRequest httpRequest) {
        rateLimiter.enforce("qr-create-ip", RequestIdentity.clientIp(httpRequest), 60, 10);
        rateLimiter.enforce("qr-create-device", RequestIdentity.device(httpRequest), 300, 10);
        return ResponseEntity.status(HttpStatus.CREATED).body(authQrService.createSession());
    }

    @PostMapping("/status")
    public ResponseEntity<QrLoginStatusResponse> status(
            @Valid @RequestBody QrLoginStatusRequest request,
            HttpServletRequest httpRequest
    ) {
        rateLimiter.enforce("qr-poll-ip", RequestIdentity.clientIp(httpRequest), 60, 60);
        rateLimiter.enforce("qr-poll-session", request.pollToken(), 60, 60);
        return ResponseEntity.ok(authQrService.getStatus(request.qrId(), request.pollToken()));
    }

    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@Valid @RequestBody QrLoginCancelRequest request,
                                       HttpServletRequest httpRequest) {
        rateLimiter.enforce("qr-cancel-ip", RequestIdentity.clientIp(httpRequest), 60, 20);
        authQrService.cancel(request.getQrId(), request.getPollToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/exchange")
    public ResponseEntity<com.example.demo.auth.dto.LoginResponse> exchange(
            @Valid @RequestBody QrLoginExchangeRequest request,
            HttpServletRequest httpRequest
    ) {
        rateLimiter.enforce("qr-exchange-ip", RequestIdentity.clientIp(httpRequest), 60, 20);
        rateLimiter.enforce("qr-exchange-session", request.getPollToken(), 300, 5);
        return ResponseEntity.ok(authQrService.exchange(request.getQrId(), request.getPollToken()));
    }

    @PostMapping("/scan")
    public ResponseEntity<QrLoginStatusResponse> scan(@Valid @RequestBody QrLoginScanRequest request) {
        return ResponseEntity.ok(authQrService.scan(request.getQrId(), getUserId()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<QrLoginStatusResponse> confirm(@Valid @RequestBody QrLoginConfirmRequest request) {
        return ResponseEntity.ok(authQrService.confirm(request.getQrId(), getUserId()));
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
