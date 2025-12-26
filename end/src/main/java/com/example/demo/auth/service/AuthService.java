package com.example.demo.auth.service;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public LoginResponse login(LoginRequest req) {
        if ((req.getEmail() == null || req.getEmail().isBlank()) && (req.getPhone() == null || req.getPhone().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须提供邮箱或手机号");
        }
        Optional<User> userOpt;
        if (req.getEmail() != null && !req.getEmail().isBlank()) {
            userOpt = userRepository.findByEmail(req.getEmail());
        } else {
            userOpt = userRepository.findByPhone(req.getPhone());
        }
        User user = userOpt.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码错误");
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        return new LoginResponse(token, dto);
    }
}