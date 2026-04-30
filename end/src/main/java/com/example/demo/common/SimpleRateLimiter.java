package com.example.demo.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;

/**
 * 简单用户级限速：某时间窗口最多允许 N 次。
 * 优先使用 Redis 固定窗口计数，支持多实例共享；
 * Redis 不可用时自动回退到进程内内存实现，便于本地开发与课程项目运行。
 */
@Component
public class SimpleRateLimiter {
    private final StringRedisTemplate redisTemplate;
    private final Map<Long, ArrayDeque<Instant>> buckets = new ConcurrentHashMap<>();

    @Autowired
    public SimpleRateLimiter(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider.getIfAvailable());
    }

    SimpleRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 校验并记录一次操作；返回是否允许。
     * @param userId 用户ID
     * @param windowSeconds 时间窗口（秒）
     * @param maxOps 最大次数
     * @return true 表示允许
     */
    public synchronized boolean allow(Long userId, int windowSeconds, int maxOps) {
        if (userId == null) return false;
        Boolean redisAllowed = allowWithRedis(userId, windowSeconds, maxOps);
        if (redisAllowed != null) {
            return redisAllowed;
        }
        return allowInMemory(userId, windowSeconds, maxOps);
    }

    private Boolean allowWithRedis(Long userId, int windowSeconds, int maxOps) {
        if (redisTemplate == null || windowSeconds <= 0 || maxOps <= 0) {
            return null;
        }
        long bucket = Instant.now().getEpochSecond() / windowSeconds;
        String key = RedisKeys.rateLimitUser(userId, windowSeconds, maxOps, bucket);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return null;
            }
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 5L));
            }
            return count <= maxOps;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean allowInMemory(Long userId, int windowSeconds, int maxOps) {
        ArrayDeque<Instant> q = buckets.computeIfAbsent(userId, k -> new ArrayDeque<>());
        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(windowSeconds);
        while (!q.isEmpty() && q.peekFirst().isBefore(threshold)) {
            q.pollFirst();
        }
        if (q.size() >= maxOps) {
            return false;
        }
        q.addLast(now);
        return true;
    }
}
