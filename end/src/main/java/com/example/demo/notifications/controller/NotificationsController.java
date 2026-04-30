package com.example.demo.notifications.controller;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {
    private final NotificationService notificationService;

    public NotificationsController(NotificationService notificationService) {
        this.notificationService = notificationService;
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
    public ResponseEntity<NotificationDto> markRead(@PathVariable("id") Long id) {
        Long userId = getUserId();
        NotificationDto updated = notificationService.markRead(userId, id);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
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
