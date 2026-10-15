package com.example.demo.common;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisCacheEvictorTest {
    @Test
    void deletes_all_matching_keys_in_bounded_batches_and_closes_cursor() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = mock(Cursor.class);
        List<String> keys = IntStream.range(0, 300).mapToObj(i -> "cache:threads:list:" + i).toList();
        AtomicInteger index = new AtomicInteger();
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenAnswer(invocation -> index.get() < keys.size());
        when(cursor.next()).thenAnswer(invocation -> keys.get(index.getAndIncrement()));

        RedisCacheEvictor.evictMatching(redis, RedisKeys.threadsListPattern());

        ArgumentCaptor<Collection<String>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(redis, times(3)).delete(batches.capture());
        assertThat(batches.getAllValues().stream().flatMap(Collection::stream).toList()).containsExactlyElementsOf(keys);
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(128));
        verify(redis).scan(argThat(options -> "cache:threads:list:*".equals(options.getPattern())));
        verify(redis, never()).keys(anyString());
        verify(cursor).close();
    }

    @Test
    void empty_scan_does_not_delete_anything() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        RedisCacheEvictor.evictMatching(redis, RedisKeys.threadsListPattern());

        verify(redis, never()).delete(anyCollection());
        verify(cursor).close();
    }

    @Test
    void closes_cursor_when_redis_delete_fails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("cache:threads:list:1");
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThatThrownBy(() -> RedisCacheEvictor.evictMatching(redis, RedisKeys.threadsListPattern()))
                .isInstanceOf(IllegalStateException.class);
        verify(cursor).close();
    }
}
