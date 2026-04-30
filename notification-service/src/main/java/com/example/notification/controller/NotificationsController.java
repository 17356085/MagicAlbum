package com.example.notification.controller;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import com.example.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "unread", required = false) Boolean unread,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Map<String, Object> body = notificationService.list(resolveUserId(userId), type, unread, page, size);
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markRead(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @PathVariable("id") Long id
    ) {
        NotificationDto updated = notificationService.markRead(resolveUserId(userId), id);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/internal")
    public ResponseEntity<NotificationDto> create(@Valid @RequestBody CreateNotificationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notificationService.create(request));
    }

    private Long resolveUserId(Long userId) {
        return userId != null ? userId : 1L;
    }
}
