package com.example.demo.auth.controller;

import com.example.demo.auth.dto.QrLoginCancelRequest;
import com.example.demo.auth.dto.QrLoginConfirmRequest;
import com.example.demo.auth.dto.QrLoginCreateResponse;
import com.example.demo.auth.dto.QrLoginScanRequest;
import com.example.demo.auth.dto.QrLoginStatusResponse;
import com.example.demo.auth.service.AuthQrService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth/qr")
public class AuthQrController {
    private final AuthQrService authQrService;

    public AuthQrController(AuthQrService authQrService) {
        this.authQrService = authQrService;
    }

    @PostMapping("/create")
    public ResponseEntity<QrLoginCreateResponse> create() {
        return ResponseEntity.status(HttpStatus.CREATED).body(authQrService.createSession());
    }

    @GetMapping("/status")
    public ResponseEntity<QrLoginStatusResponse> status(@RequestParam("qrId") String qrId) {
        return ResponseEntity.ok(authQrService.getStatus(qrId));
    }

    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@Valid @RequestBody QrLoginCancelRequest request) {
        authQrService.cancel(request.getQrId());
        return ResponseEntity.noContent().build();
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
