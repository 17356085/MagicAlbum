package com.example.demo.threads.dto;

import java.time.Instant;

/** Lightweight public item used by the section preview endpoint. */
public record ThreadPreviewDto(
        String id,
        String title,
        String authorId,
        String authorUsername,
        String authorNickname,
        Instant createdAt,
        String coverUrl
) {
}
