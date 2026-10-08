package com.example.demo.sections.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.common.RedisKeys;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class SectionService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final SectionRepository sectionRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    SectionService(SectionRepository sectionRepository) {
        this(sectionRepository, null, defaultObjectMapper());
    }

    @Autowired
    public SectionService(SectionRepository sectionRepository,
                          ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                          ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(sectionRepository,
                redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(),
                resolveObjectMapper(objectMapperProvider));
    }

    SectionService(SectionRepository sectionRepository, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.sectionRepository = sectionRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? defaultObjectMapper() : objectMapper;
    }

    public Page<Section> list(String q, int page, int size) {
        CachedSectionPage cached = readCache(cacheKey(q, page, size));
        if (cached != null) {
            return new PageImpl<>(cached.items(), PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100)), cached.total());
        }
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100));
        Page<Section> result = sectionRepository.search(q == null || q.isBlank() ? null : q, pr);
        writeCache(cacheKey(q, page, size), new CachedSectionPage(result.getContent(), result.getTotalElements()));
        return result;
    }

    private CachedSectionPage readCache(String key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CachedSectionPage.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCache(String key, CachedSectionPage payload) {
        if (redisTemplate == null || payload == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(payload), CACHE_TTL);
        } catch (Exception ignored) {
        }
    }

    private String cacheKey(String q, int page, int size) {
        return RedisKeys.sectionsList(q, page, size);
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static ObjectMapper resolveObjectMapper(ObjectProvider<ObjectMapper> provider) {
        ObjectMapper mapper = provider == null ? null : provider.getIfAvailable();
        return mapper == null ? defaultObjectMapper() : mapper;
    }

    private record CachedSectionPage(List<Section> items, long total) {
    }
}
