package com.example.demo.user.connected;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.connected.dto.VerifiedDisconnectRequest;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import com.example.demo.user.connected.service.VerifiedConnectedAccountRemovalService;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me/connected-accounts")
public class UserConnectedAccountsController {
    private final ConnectedAccountsService service;
    private final VerifiedConnectedAccountRemovalService removalService;
    private final DistributedRateLimiter rateLimiter;

    public UserConnectedAccountsController(ConnectedAccountsService service,
                                           VerifiedConnectedAccountRemovalService removalService,
                                           DistributedRateLimiter rateLimiter) {
        this.service = service;
        this.removalService = removalService;
        this.rateLimiter = rateLimiter;
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

    @PostMapping("/{provider}/disconnect")
    public ResponseEntity<Void> disconnectWithLocalPassword(
            @PathVariable("provider") String provider,
            @Valid @RequestBody VerifiedDisconnectRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = getUserId();
        rateLimiter.enforce("login-ip", RequestIdentity.clientIp(httpRequest), 60, 30);
        rateLimiter.enforce("login-device", RequestIdentity.device(httpRequest), 600, 20);
        removalService.disconnect(userId, provider, request.identifier(), request.currentPassword(), request.bindingTag());
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
