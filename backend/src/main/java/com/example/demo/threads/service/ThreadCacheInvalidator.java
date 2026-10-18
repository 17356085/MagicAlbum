package com.example.demo.threads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Shared cache invalidation for writes that affect a thread and its public comments.
 * Keeping this beside {@link ThreadCache} avoids a service-to-service dependency cycle.
 */
public final class ThreadCacheInvalidator {
    private final ThreadCache cache;

    public ThreadCacheInvalidator(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.cache = new ThreadCache(redisTemplate, objectMapper);
    }

    public void evictThreadPosts(Long threadId) {
        cache.evictThreadPosts(threadId);
    }

    public void evictThreadMetadata(Long threadId) {
        cache.evictLists();
        cache.evictDetail(threadId);
    }

    public void evictThread(Long threadId) {
        evictThreadPosts(threadId);
        evictThreadMetadata(threadId);
    }
}
