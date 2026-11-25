package com.example.demo.threads.dto;

import java.util.List;

public record FollowingFeedPage(
        List<ThreadDto> items,
        int page,
        int size,
        long total,
        String nextCursor,
        String previousCursor
) {}
