package com.example.demo.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSummaryCacheServiceTest {

    @Test
    void shouldPutAndReadCachedSummary() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        AiSummaryCacheService service = new AiSummaryCacheService(redisTemplate, mapper);
        service.put(7L, "summary text", "COMPLETED");

        String json = mapper.writeValueAsString(new AiSummaryCacheService.CachedAiSummary("summary text", "COMPLETED"));
        when(valueOperations.get("cache:ai:summary:7")).thenReturn(json);

        AiSummaryCacheService.CachedAiSummary cached = service.get(7L);
        assertThat(cached).isNotNull();
        assertThat(cached.summary()).isEqualTo("summary text");
        assertThat(cached.status()).isEqualTo("COMPLETED");
        verify(valueOperations).set(anyString(), anyString(), any());
    }
}
