package com.example.demo.bookmarks.dto;

import java.time.Instant;

public record BookmarkItem(
        Long threadId,
        String title,
        String sectionName,
        Instant bookmarkedAt
) {
}
