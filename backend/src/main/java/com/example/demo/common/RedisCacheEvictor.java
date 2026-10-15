package com.example.demo.common;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

/** Incremental cache invalidation without blocking Redis with KEYS. */
public final class RedisCacheEvictor {
    private static final int DELETE_BATCH_SIZE = 128;

    private RedisCacheEvictor() {
    }

    public static void evictMatching(StringRedisTemplate redisTemplate, String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(DELETE_BATCH_SIZE).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            List<String> batch = new ArrayList<>(DELETE_BATCH_SIZE);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == DELETE_BATCH_SIZE) {
                    redisTemplate.delete(List.copyOf(batch));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redisTemplate.delete(batch);
            }
        }
    }
}
