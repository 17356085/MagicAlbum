package com.example.demo.threads.dto;

import java.time.Instant;
import java.util.List;

public record ThreadSearchDocument(
        long id,
        long version,
        boolean visible,
        String title,
        String content,
        List<String> tags,
        Long sectionId,
        Instant createdAt,
        String eventId,
        String eventType
) {
}
