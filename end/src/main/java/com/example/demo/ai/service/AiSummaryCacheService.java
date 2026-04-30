package com.example.demo.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.common.RedisKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AiSummaryCacheService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiSummaryCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                 ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(),
                resolveObjectMapper(objectMapperProvider));
    }

    AiSummaryCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? defaultObjectMapper() : objectMapper;
    }

    public CachedAiSummary get(Long threadId) {
        if (redisTemplate == null || threadId == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(threadId));
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CachedAiSummary.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void put(Long threadId, String summary, String status) {
        if (redisTemplate == null || threadId == null) {
            return;
        }
        try {
            CachedAiSummary payload = new CachedAiSummary(
                    summary == null ? "" : summary,
                    status == null || status.isBlank() ? "PENDING" : status
            );
            redisTemplate.opsForValue().set(cacheKey(threadId), objectMapper.writeValueAsString(payload), CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    public void evict(Long threadId) {
        if (redisTemplate == null || threadId == null) {
            return;
        }
        try {
            redisTemplate.delete(cacheKey(threadId));
        } catch (Exception ignored) {
        }
    }

    private String cacheKey(Long threadId) {
        return RedisKeys.aiSummary(threadId);
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static ObjectMapper resolveObjectMapper(ObjectProvider<ObjectMapper> provider) {
        ObjectMapper mapper = provider == null ? null : provider.getIfAvailable();
        return mapper == null ? defaultObjectMapper() : mapper;
    }

    public record CachedAiSummary(String summary, String status) {
    }
}
