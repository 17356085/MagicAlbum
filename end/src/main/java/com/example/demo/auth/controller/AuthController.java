package com.example.demo.auth.controller;

import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        LoginResponse resp = authService.login(req);
        return ResponseEntity.ok(resp);
    }
}