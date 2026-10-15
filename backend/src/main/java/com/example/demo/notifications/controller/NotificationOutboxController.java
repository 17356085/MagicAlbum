package com.example.demo.notifications.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.notifications.outbox.NotificationOutboxService;
import com.example.demo.notifications.outbox.NotificationOutboxStatus;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
@RequestMapping("/api/v1/admin/notification-outbox")
public class NotificationOutboxController {
    private final NotificationOutboxService outbox;
    private final AdminAccessService access;

    public NotificationOutboxController(NotificationOutboxService outbox, AdminAccessService access) {
        this.outbox = outbox;
        this.access = access;
    }

    @GetMapping
    public Map<NotificationOutboxStatus, Long> counts(@AuthenticationPrincipal Long userId) {
        access.requireAdmin(userId);
        var counts = new EnumMap<NotificationOutboxStatus, Long>(NotificationOutboxStatus.class);
        for (var status : NotificationOutboxStatus.values()) counts.put(status, outbox.count(status));
        return counts;
    }

    @GetMapping("/{id}")
    public DeliveryStatus status(@AuthenticationPrincipal Long userId, @PathVariable @Positive Long id) {
        access.requireAdmin(userId);
        var row = outbox.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知事件不存在"));
        return new DeliveryStatus(row.getId(), row.getEventId(), row.getStatus(), row.getAttempts(),
                row.getAvailableAt(), row.getDeliveredAt(), row.getLastError());
    }

    @PostMapping("/{id}/retry")
    public Map<String, Boolean> retry(@AuthenticationPrincipal Long userId, @PathVariable @Positive Long id) {
        access.requireAdmin(userId);
        if (!outbox.manualRetry(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅失败的通知事件可以重新投递");
        }
        return Map.of("queued", true);
    }

    public record DeliveryStatus(Long id, String eventId, NotificationOutboxStatus status, int attempts,
                                 Instant availableAt, Instant deliveredAt, String lastError) {
    }
}
