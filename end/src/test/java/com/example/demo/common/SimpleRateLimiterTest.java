package com.example.demo.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleRateLimiterTest {

    @Test
    void shouldAllowAndBlockUsingRedisCounter() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L, 2L, 3L);

        SimpleRateLimiter limiter = new SimpleRateLimiter(redisTemplate);

        assertTrue(limiter.allow(1001L, 60, 2));
        assertTrue(limiter.allow(1001L, 60, 2));
        assertFalse(limiter.allow(1001L, 60, 2));
        verify(redisTemplate, times(1)).expire(anyString(), any(Duration.class));
    }

    @Test
    void shouldFallbackToInMemoryWhenRedisUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        SimpleRateLimiter limiter = new SimpleRateLimiter(redisTemplate);

        assertTrue(limiter.allow(1002L, 60, 2));
        assertTrue(limiter.allow(1002L, 60, 2));
        assertFalse(limiter.allow(1002L, 60, 2));
    }
}
