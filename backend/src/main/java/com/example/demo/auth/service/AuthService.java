package com.example.demo.auth.service;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.auth.service.otp.AuthVerifyService;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.entity.User;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final LocalPasswordAuthenticator passwordAuthenticator;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthVerifyService authVerifyService;

    public AuthService(LocalPasswordAuthenticator passwordAuthenticator, JwtTokenProvider jwtTokenProvider, AuthVerifyService authVerifyService) {
        this.passwordAuthenticator = passwordAuthenticator;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authVerifyService = authVerifyService;
    }

    public LoginResponse login(LoginRequest req) {
        authVerifyService.verify(req.getVerifyToken(), req.getVerifyProvider(), req.getVerifyScene(), "login");
        User user = passwordAuthenticator.authenticate(req.getEmail(), req.getPhone(), req.getPassword());

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        return new LoginResponse(token, dto);
    }
}
