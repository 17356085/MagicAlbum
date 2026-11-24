package com.example.demo.posts.cache;

import com.example.demo.common.RedisKeys;
import com.example.demo.posts.port.PostPageCache;
import com.example.demo.threads.service.ThreadCacheInvalidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis/Jackson adapter for the Posts anonymous page cache. */
@Component
public final class RedisPostPageCache implements PostPageCache {
    private static final Duration THREAD_POSTS_CACHE_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ThreadCacheInvalidator threadCacheInvalidator;

    @Autowired
    public RedisPostPageCache(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                              ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(),
                objectMapperProvider == null ? null : objectMapperProvider.getIfAvailable());
    }

    public RedisPostPageCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? defaultObjectMapper() : objectMapper;
        this.threadCacheInvalidator = new ThreadCacheInvalidator(redisTemplate, this.objectMapper);
    }

    @Override
    public CachedPostPage read(Long threadId, String sort, int page, int size) {
        if (redisTemplate == null || threadId == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key(threadId, sort, page, size));
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CachedPostPage.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void write(Long threadId, String sort, int page, int size, CachedPostPage payload) {
        if (redisTemplate == null || threadId == null || payload == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(threadId, sort, page, size),
                    objectMapper.writeValueAsString(payload),
                    THREAD_POSTS_CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void evictThreadPosts(Long threadId) {
        threadCacheInvalidator.evictThreadPosts(threadId);
    }

    @Override
    public void evictThread(Long threadId) {
        threadCacheInvalidator.evictThread(threadId);
    }

    private String key(Long threadId, String sort, int page, int size) {
        return RedisKeys.threadPosts(threadId, sort, page, size);
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
