package com.example.demo.ai.service;

import com.example.demo.common.DistributedRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiQuotaServiceTest {
    @Test
    void localLeaseCloseIsIdempotentAndReleasesTheExactPerUserCount() {
        AiQuotaService service = localService(1);

        AiQuotaService.Lease first = service.acquire(42L, "127.0.0.1");
        assertThatThrownBy(() -> service.acquire(42L, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));

        first.close();
        first.close();
        AiQuotaService.Lease second = service.acquire(42L, "127.0.0.1");
        second.close();
    }

    @Test
    void localAcquireAndCloseRemainBoundedUnderConcurrentRaces() throws Exception {
        int maxConcurrent = 2;
        AiQuotaService service = localService(maxConcurrent);
        int workers = 8;
        int iterations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try {
            List<Future<Object>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        for (int i = 0; i < iterations; i++) {
                            try {
                                AiQuotaService.Lease lease = service.acquire(42L, "127.0.0.1");
                                int nowActive = active.incrementAndGet();
                                maximum.accumulateAndGet(nowActive, Math::max);
                                Thread.yield();
                                active.decrementAndGet();
                                lease.close();
                            } catch (ResponseStatusException expected) {
                                assertThat(expected.getStatusCode().value())
                                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
                                Thread.yield();
                            }
                        }
                        return null;
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            assertThat(active.get()).isZero();
            assertThat(maximum.get()).isLessThanOrEqualTo(maxConcurrent);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void redisAcquireUsesTheNewLeaseSetNamespaceAndAtomicScriptArguments() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AtomicReference<RedisScript<Long>> script = new AtomicReference<>();
        AtomicReference<List<String>> keys = new AtomicReference<>();
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        doAnswer(invocation -> {
            script.set(invocation.getArgument(0));
            keys.set(invocation.getArgument(1));
            arguments.set((Object[]) invocation.getRawArguments()[2]);
            return 1L;
        }).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        AiQuotaService.Lease lease = redisService(redis, true, 3).acquire(42L, "127.0.0.1");
        assertThat(script.get()).isNotNull();
        assertThat(keys.get()).containsExactly("ai:concurrent:leases:user:42");
        assertThat(arguments.get()).hasSize(3);
        assertThat(arguments.get()[0]).isEqualTo("3");
        assertThat(arguments.get()[1]).isEqualTo("180000");
        assertThat(arguments.get()[2].toString()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        lease.close();
    }

    @Test
    void redisReleaseUsesOnlyTheLeaseMemberAndNeverDeletesTheSet() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AtomicReference<List<String>> releaseKeys = new AtomicReference<>();
        AtomicReference<Object[]> releaseArguments = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                releaseKeys.set(invocation.getArgument(1));
                releaseArguments.set((Object[]) invocation.getRawArguments()[2]);
            }
            return 1L;
        }).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        AiQuotaService.Lease lease = redisService(redis, true, 2).acquire(7L, "127.0.0.1");
        lease.close();
        lease.close();

        assertThat(calls).hasValue(2);
        assertThat(releaseKeys.get()).containsExactly("ai:concurrent:leases:user:7");
        assertThat(releaseArguments.get()).hasSize(1);
        assertThat(releaseArguments.get()[0].toString()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        verify(redis, never()).delete(any(String.class));
        verify(redis, never()).opsForValue();
    }

    @Test
    void redisOverLimitIsRejectedWithoutUsingLocalFallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doAnswer(invocation -> 0L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        AiQuotaService service = redisService(redis, false, 1);

        assertThatThrownBy(() -> service.acquire(42L, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
    }

    @Test
    void redisFailureIsFailClosedWhenConfigured() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> redisService(redis, true, 1).acquire(42L, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    @Test
    void redisFailureUsesLocalFallbackOnlyWhenExplicitlyAllowed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        AiQuotaService service = redisService(redis, false, 1);

        AiQuotaService.Lease lease = service.acquire(42L, "127.0.0.1");
        lease.close();
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    private static AiQuotaService localService(int maxConcurrent) {
        return new AiQuotaService(noOpRateLimiter(), null, false, maxConcurrent);
    }

    @SuppressWarnings("unchecked")
    private static AiQuotaService redisService(StringRedisTemplate redis, boolean failClosed, int maxConcurrent) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(redis);
        return new AiQuotaService(noOpRateLimiter(), provider, failClosed, maxConcurrent);
    }

    private static DistributedRateLimiter noOpRateLimiter() {
        return mock(DistributedRateLimiter.class);
    }

}
