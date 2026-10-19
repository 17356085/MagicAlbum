package com.example.demo.threads.event.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Stable content event envelope. The event id is retained for every retry.
 */
public record ContentEventMessage(
        String eventId,
        int schemaVersion,
        String aggregateType,
        long aggregateId,
        long aggregateVersion,
        String eventType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        String requestId
) {
}
