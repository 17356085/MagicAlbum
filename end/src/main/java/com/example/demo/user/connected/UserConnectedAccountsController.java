package com.example.demo.user.connected;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me/connected-accounts")
public class UserConnectedAccountsController {
    private final ConnectedAccountsService service;

    public UserConnectedAccountsController(ConnectedAccountsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        Long userId = getUserId();
        Map<String, Object> body = service.list(userId);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{provider}/connect")
    public ResponseEntity<ConnectedAccountDto> connect(
            @PathVariable("provider") String provider
    ) {
        Long userId = getUserId();
        ConnectedAccountDto dto = service.connect(userId, provider);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disconnect(
            @PathVariable("provider") String provider
    ) {
        Long userId = getUserId();
        service.disconnect(userId, provider);
        return ResponseEntity.noContent().build();
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
