package com.example.demo.notifications.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.notifications.outbox.NotificationOutboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/admin/notifications/outbox")
public class NotificationOutboxAdminController {
    private final AdminAccessService adminAccessService;
    private final NotificationOutboxService outboxService;

    public NotificationOutboxAdminController(
            AdminAccessService adminAccessService,
            NotificationOutboxService outboxService
    ) {
        this.adminAccessService = adminAccessService;
        this.outboxService = outboxService;
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id, Authentication authentication) {
        adminAccessService.requireAdmin(currentUserId(authentication));
        if (!outboxService.manualRetry(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "可重试通知不存在");
        }
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Number number ? number.longValue() : null;
    }
}
