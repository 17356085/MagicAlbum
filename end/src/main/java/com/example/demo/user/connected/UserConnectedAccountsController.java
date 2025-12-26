package com.example.demo.user.connected;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.connected.dto.ConnectedAccountDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me/connected-accounts")
public class UserConnectedAccountsController {
    private final JwtTokenProvider jwtTokenProvider;
    private final UserConnectedAccountsService service;

    public UserConnectedAccountsController(JwtTokenProvider jwtTokenProvider, UserConnectedAccountsService service) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    ) {
        Long userId = requireLogin(authorization);
        Map<String, Object> body = service.list(userId);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{provider}/connect")
    public ResponseEntity<ConnectedAccountDto> connect(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("provider") String provider
    ) {
        Long userId = requireLogin(authorization);
        ConnectedAccountDto dto = service.connect(userId, provider);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disconnect(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("provider") String provider
    ) {
        Long userId = requireLogin(authorization);
        service.disconnect(userId, provider);
        return ResponseEntity.noContent().build();
    }

    private Long requireLogin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌缺失");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌无效或已过期");
        }
        return userId;
    }
}