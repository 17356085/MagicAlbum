package com.example.demo.governance.dto;

import java.time.Instant;

public record AuditView(
        Long id,
        Long actorId,
        String action,
        String targetType,
        Long targetId,
        String reason,
        Instant occurredAt,
        String requestId
) {
}
