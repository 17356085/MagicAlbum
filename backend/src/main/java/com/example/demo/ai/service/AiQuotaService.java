package com.example.demo.ai.service;

import com.example.demo.common.DistributedRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiQuotaService {
    private static final String REDIS_KEY_PREFIX = "ai:concurrent:leases:user:";
    private static final long LEASE_TTL_MILLIS = Duration.ofSeconds(180).toMillis();
    private static final RedisScript<Long> ACQUIRE_SCRIPT = loadScript("redis/ai-quota-acquire.lua");
    private static final RedisScript<Long> RELEASE_SCRIPT = loadScript("redis/ai-quota-release.lua");

    private final DistributedRateLimiter rateLimiter;
    private final StringRedisTemplate redisTemplate;
    private final boolean failClosed;
    private final int maxConcurrent;
    private final ConcurrentHashMap<Long, Integer> localInFlight = new ConcurrentHashMap<>();

    private static RedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

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
            try {
                return acquireRedis(userId);
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

        if (!acquireLocal(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 请求正在生成，请稍后再试");
        }
        return new Lease(() -> releaseLocal(userId));
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

    private Lease acquireRedis(Long userId) {
        String key = REDIS_KEY_PREFIX + userId;
        String member = UUID.randomUUID().toString();
        Long accepted = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(key),
                String.valueOf(maxConcurrent),
                String.valueOf(LEASE_TTL_MILLIS),
                member);
        if (accepted == null) {
            throw new IllegalStateException("AI concurrency lease unavailable");
        }
        if (accepted != 1L) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 请求正在生成，请稍后再试");
        }
        return new Lease(() -> releaseRedis(key, member));
    }

    private boolean acquireLocal(Long userId) {
        AtomicBoolean acquired = new AtomicBoolean();
        localInFlight.compute(userId, (ignored, current) -> {
            int count = current == null ? 0 : current;
            if (count >= maxConcurrent) {
                return current;
            }
            acquired.set(true);
            return count + 1;
        });
        return acquired.get();
    }

    private void releaseRedis(String key, String member) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), member);
        } catch (Exception ex) {
            if (failClosed) {
                throw new IllegalStateException("AI 配额释放失败", ex);
            }
        }
    }

    private void releaseLocal(Long userId) {
        localInFlight.computeIfPresent(userId, (ignored, current) -> current <= 1 ? null : current - 1);
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
