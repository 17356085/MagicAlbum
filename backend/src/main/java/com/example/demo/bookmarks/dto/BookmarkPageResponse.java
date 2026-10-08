package com.example.demo.bookmarks.dto;

import java.util.List;

public record BookmarkPageResponse(
        List<BookmarkItem> items,
        int page,
        int size,
        long total
) {
}
