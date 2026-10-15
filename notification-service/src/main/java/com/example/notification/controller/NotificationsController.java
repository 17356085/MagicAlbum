package com.example.notification.controller;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import com.example.notification.dto.NotificationPageResponse;
import com.example.notification.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/notifications")
@Validated
public class NotificationsController {
    private final NotificationService notificationService;

    public NotificationsController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<NotificationPageResponse> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "type", required = false) @Size(max = 40) String type,
            @RequestParam(value = "unread", required = false) Boolean unread,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        NotificationPageResponse body = notificationService.list(requireUserId(userId), type, unread, page, size);
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markRead(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") @Positive Long id
    ) {
        NotificationDto updated = notificationService.markRead(requireUserId(userId), id);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/internal")
    public ResponseEntity<NotificationDto> create(@Valid @RequestBody CreateNotificationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notificationService.create(request));
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要有效的用户认证");
        }
        return userId;
    }
}
