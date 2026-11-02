package com.example.demo.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.Clock;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributedRateLimiterRetentionTest {
    @Test
    void local_fallback_discards_old_time_buckets() throws Exception {
        DistributedRateLimiter limiter = new DistributedRateLimiter((StringRedisTemplate) null, false);
        assertThat(limiter.allow("retention-test", "first", 1, 1)).isTrue();
        long bucket = Instant.now().getEpochSecond();
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (Instant.now().getEpochSecond() == bucket && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(Instant.now().getEpochSecond()).isGreaterThan(bucket);
        assertThat(limiter.allow("retention-test", "second", 1, 1)).isTrue();
        Field field = DistributedRateLimiter.class.getDeclaredField("localWindows");
        field.setAccessible(true);
        assertThat((Map<?, ?>) field.get(limiter)).hasSize(1);
    }

    @Test
    void different_windows_expire_independently_and_backward_clock_does_not_reopen_a_bucket() throws Exception {
        Clock clock = mock(Clock.class);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(120));
        when(clock.instant()).thenAnswer(ignored -> now.get());
        DistributedRateLimiter limiter = new DistributedRateLimiter(null, false, clock);
        assertThat(limiter.allow("short", "user", 1, 1)).isTrue();
        assertThat(limiter.allow("long", "user", 60, 1)).isTrue();
        assertThat(limiter.allow("long", "user", 60, 1)).isFalse();

        now.set(Instant.ofEpochSecond(121));
        assertThat(limiter.allow("short", "user", 1, 1)).isTrue();
        assertThat(limiter.allow("long", "user", 60, 1)).isFalse();
        Field field = DistributedRateLimiter.class.getDeclaredField("localWindows");
        field.setAccessible(true);
        assertThat((Map<?, ?>) field.get(limiter)).hasSize(2);

        now.set(Instant.ofEpochSecond(120));
        assertThat(limiter.allow("short", "user", 1, 1)).isFalse();
        now.set(Instant.ofEpochSecond(180));
        assertThat(limiter.allow("long", "user", 60, 1)).isTrue();
        assertThat((Map<?, ?>) field.get(limiter)).hasSize(1);
    }

    @Test
    void a_request_delayed_past_its_bucket_is_not_reopened_after_cleanup() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(120), Instant.ofEpochSecond(121));
        DistributedRateLimiter limiter = new DistributedRateLimiter(null, false, clock);
        assertThat(limiter.allow("delayed", "user", 1, 1)).isFalse();
    }

    @Test
    void clock_rollback_between_bucket_selection_and_fallback_is_rejected() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(121), Instant.ofEpochSecond(120));
        DistributedRateLimiter limiter = new DistributedRateLimiter(null, false, clock);
        assertThat(limiter.allow("rollback", "user", 1, 1)).isFalse();
    }

    @Test
    void concurrent_requests_share_the_same_local_limit() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(120), java.time.ZoneOffset.UTC);
        DistributedRateLimiter limiter = new DistributedRateLimiter(null, false, clock);
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for (int i = 0; i < 20; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.allow("concurrency", "user", 60, 5);
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(5);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
