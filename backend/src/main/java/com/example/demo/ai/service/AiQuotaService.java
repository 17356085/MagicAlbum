package com.example.demo.ai.service;

import com.example.demo.common.DistributedRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiQuotaService {
    private final DistributedRateLimiter rateLimiter;
    private final StringRedisTemplate redisTemplate;
    private final boolean failClosed;
    private final int maxConcurrent;
    private final ConcurrentHashMap<Long, AtomicInteger> localInFlight = new ConcurrentHashMap<>();

    public AiQuotaService(
            DistributedRateLimiter rateLimiter,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.ai.rate-limit.fail-closed:true}") boolean failClosed,
            @Value("${app.ai.max-concurrent-per-user:2}") int maxConcurrent
    ) {
        this.rateLimiter = rateLimiter;
        this.redisTemplate = redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
        this.failClosed = failClosed;
        this.maxConcurrent = Math.max(1, Math.min(maxConcurrent, 8));
    }

    public Lease acquire(Long userId, String clientIp) {
        enforceRequest(userId, clientIp);

        if (redisTemplate != null) {
            String key = "rate_limit:ai-concurrent:user:" + userId;
            try {
                Long current = redisTemplate.opsForValue().increment(key);
                if (current == null) {
                    throw new IllegalStateException("AI concurrency counter unavailable");
                }
                if (current == 1L) {
                    redisTemplate.expire(key, Duration.ofMinutes(3));
                }
                if (current > maxConcurrent) {
                    redisTemplate.opsForValue().decrement(key);
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 请求正在生成，请稍后再试");
                }
                return new Lease(() -> releaseRedis(key));
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                if (failClosed) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 配额服务暂不可用");
                }
            }
        } else if (failClosed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 配额服务未配置");
        }

        AtomicInteger count = localInFlight.computeIfAbsent(userId, ignored -> new AtomicInteger());
        if (count.incrementAndGet() > maxConcurrent) {
            count.decrementAndGet();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 请求正在生成，请稍后再试");
        }
        return new Lease(() -> releaseLocal(userId, count));
    }

    /**
     * Enforces the shared per-user and per-IP AI request budget for both
     * synchronous streams and asynchronous jobs such as thread summaries.
     */
    public void enforceRequest(Long userId, String clientIp) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        rateLimiter.enforce("ai-user-minute", String.valueOf(userId), 60, 10);
        rateLimiter.enforce("ai-ip-minute", clientIp, 60, 30);
        rateLimiter.enforce("ai-user-daily", String.valueOf(userId), 86_400, 100);
    }

    private void releaseRedis(String key) {
        try {
            Long remaining = redisTemplate.opsForValue().decrement(key);
            if (remaining != null && remaining <= 0L) {
                redisTemplate.delete(key);
            }
        } catch (Exception ex) {
            if (failClosed) {
                throw new IllegalStateException("AI 配额释放失败", ex);
            }
        }
    }

    private void releaseLocal(Long userId, AtomicInteger count) {
        if (count.decrementAndGet() <= 0) {
            localInFlight.remove(userId, count);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Runnable releaseAction;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Runnable releaseAction) {
            this.releaseAction = releaseAction;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                releaseAction.run();
            }
        }
    }
}
