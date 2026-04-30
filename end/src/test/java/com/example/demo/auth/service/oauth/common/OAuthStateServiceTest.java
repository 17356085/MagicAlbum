package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.OAuthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthStateServiceTest {

    @Test
    void shouldIssueAndConsumeStateUsingRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        OAuthStateService service = new OAuthStateService(300, redisTemplate);
        String state = service.issue(OAuthProvider.github);
        when(valueOperations.getAndDelete("oauth:state:" + state)).thenReturn(OAuthProvider.github.name());

        assertDoesNotThrow(() -> service.consume(OAuthProvider.github, state));
        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
        verify(valueOperations).getAndDelete("oauth:state:" + state);
    }

    @Test
    void shouldFallbackToInMemoryWhenRedisUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        OAuthStateService service = new OAuthStateService(300, redisTemplate);
        String state = service.issue(OAuthProvider.google);

        assertDoesNotThrow(() -> service.consume(OAuthProvider.google, state));
    }

    @Test
    void shouldRejectStateWhenProviderMismatch() {
        OAuthStateService service = OAuthStateService.forTest(300);
        String state = service.issue(OAuthProvider.apple);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.consume(OAuthProvider.github, state));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals("OAuth state 与当前 Provider 不匹配", ex.getReason());
    }

    @Test
    void shouldConsumeSafelyReturnFalseForInvalidState() {
        OAuthStateService service = OAuthStateService.forTest(300);

        assertFalse(service.consumeSafely(OAuthProvider.wechat, "missing"));
    }
}
