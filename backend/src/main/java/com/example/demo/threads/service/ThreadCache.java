package com.example.demo.threads.service;

import com.example.demo.common.RedisCacheEvictor;
import com.example.demo.common.RedisKeys;
import com.example.demo.threads.dto.ThreadDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Optional Redis acceleration. Cache failures fall back to the authoritative database. */
final class ThreadCache {
    private static final Duration THREAD_LIST_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration THREAD_DETAIL_CACHE_TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    ThreadCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    CachedThreadPage readList(String key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            if (json.isBlank()) {
                deleteQuietly(key);
                return null;
            }
            CachedThreadPage payload = objectMapper.readValue(json, CachedThreadPage.class);
            if (!isValidList(payload)) {
                deleteQuietly(key);
                return null;
            }
            return payload;
        } catch (Exception ignored) {
            deleteQuietly(key);
            return null;
        }
    }

    void writeList(String key, CachedThreadPage payload) {
        if (redisTemplate == null || payload == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(payload), THREAD_LIST_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    void evictLists() {
        if (redisTemplate == null) {
            return;
        }
        try {
            RedisCacheEvictor.evictMatching(redisTemplate, RedisKeys.threadsListPattern());
        } catch (Exception ignored) {
        }
    }

    ThreadDto readDetail(Long threadId) {
        if (redisTemplate == null || threadId == null) {
            return null;
        }
        String key = RedisKeys.threadDetail(threadId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            if (json.isBlank()) {
                deleteQuietly(key);
                return null;
            }
            ThreadDto dto = objectMapper.readValue(json, ThreadDto.class);
            if (!isValidDetail(threadId, dto)) {
                deleteQuietly(key);
                return null;
            }
            return dto;
        } catch (Exception ignored) {
            deleteQuietly(key);
            return null;
        }
    }

    void writeDetail(Long threadId, ThreadDto dto) {
        if (redisTemplate == null || threadId == null || dto == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(RedisKeys.threadDetail(threadId), objectMapper.writeValueAsString(dto), THREAD_DETAIL_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    void evictDetail(Long threadId) {
        if (redisTemplate == null || threadId == null) {
            return;
        }
        deleteQuietly(RedisKeys.threadDetail(threadId));
    }

    private boolean isValidList(CachedThreadPage payload) {
        if (payload == null || payload.items() == null || payload.total() < 0 || payload.total() < payload.items().size()) {
            return false;
        }
        return payload.items().stream().allMatch(this::isValidThread);
    }

    private boolean isValidDetail(Long threadId, ThreadDto dto) {
        return isValidThread(dto)
                && threadId.equals(dto.getId());
    }

    private boolean isValidThread(ThreadDto dto) {
        return dto != null
                && isPositive(dto.getId())
                && isPositive(dto.getSectionId())
                && isPositive(dto.getAuthorId())
                && dto.getTitle() != null
                && !dto.getTitle().isBlank()
                && "NORMAL".equals(dto.getStatus());
    }

    private boolean isPositive(Long value) {
        return value != null && value > 0;
    }

    private void deleteQuietly(String key) {
        if (redisTemplate == null || key == null) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }

    record CachedThreadPage(List<ThreadDto> items, long total) {
    }
}
