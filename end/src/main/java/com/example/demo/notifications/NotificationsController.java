package com.example.demo.notifications;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.user.dto.UserSettingsDto;
import com.example.demo.user.service.UserSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * @author 17356
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {
    private final NotificationService notificationService;
    private final UserSettingsService userSettingsService;

    public NotificationsController(NotificationService notificationService, UserSettingsService userSettingsService) {
        this.notificationService = notificationService;
        this.userSettingsService = userSettingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "unread", required = false) Boolean unread,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Long userId = getUserId();
        Map<String, Object> body = notificationService.list(userId, type, unread, page, size);
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markRead(
            @PathVariable("id") Long id
    ) {
        Long userId = getUserId();
        NotificationDto updated = notificationService.markRead(userId, id);
        if (updated == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/settings")
    public ResponseEntity<UserSettingsDto> getSettings() {
        Long userId = getUserId();
        UserSettingsDto settings = userSettingsService.getSettings(userId);
        return ResponseEntity.ok(settings);
    }

    @PatchMapping("/settings")
    public ResponseEntity<UserSettingsDto> updateSettings(
            @RequestBody UserSettingsDto payload
    ) {
        Long userId = getUserId();
        UserSettingsDto updated = userSettingsService.updateSettings(userId, payload);
        return ResponseEntity.ok(updated);
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
