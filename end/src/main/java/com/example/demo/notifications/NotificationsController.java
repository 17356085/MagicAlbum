package com.example.demo.notifications;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.user.dto.UserSettingsDto;
import com.example.demo.user.service.UserSettingsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {
    private final JwtTokenProvider jwtTokenProvider;
    private final NotificationService notificationService;
    private final UserSettingsService userSettingsService;

    public NotificationsController(JwtTokenProvider jwtTokenProvider, NotificationService notificationService, UserSettingsService userSettingsService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.notificationService = notificationService;
        this.userSettingsService = userSettingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "unread", required = false) Boolean unread,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Long userId = requireLogin(authorization);
        Map<String, Object> body = notificationService.list(userId, type, unread, page, size);
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markRead(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("id") Long id
    ) {
        Long userId = requireLogin(authorization);
        NotificationDto updated = notificationService.markRead(userId, id);
        if (updated == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/settings")
    public ResponseEntity<UserSettingsDto> getSettings(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    ) {
        Long userId = requireLogin(authorization);
        UserSettingsDto settings = userSettingsService.getSettings(userId);
        return ResponseEntity.ok(settings);
    }

    @PatchMapping("/settings")
    public ResponseEntity<UserSettingsDto> updateSettings(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody UserSettingsDto payload
    ) {
        Long userId = requireLogin(authorization);
        UserSettingsDto updated = userSettingsService.updateSettings(userId, payload);
        return ResponseEntity.ok(updated);
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