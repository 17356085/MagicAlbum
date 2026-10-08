package com.example.demo.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 简单用户级限速：某时间窗口最多允许 N 次。
 * 优先使用 Redis 固定窗口计数，支持多实例共享；
 * 此遗留适配器不再在 Redis 故障时静默降级；业务限流请使用
 * DistributedRateLimiter，并由 profile 显式决定是否允许本地内存降级。
 */
@Component
public class SimpleRateLimiter {
    private final StringRedisTemplate redisTemplate;

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
    public boolean allow(Long userId, int windowSeconds, int maxOps) {
        if (userId == null) return false;
        if (redisTemplate == null || windowSeconds <= 0 || maxOps <= 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务未配置");
        }
        long bucket = Instant.now().getEpochSecond() / windowSeconds;
        String key = RedisKeys.rateLimitUser(userId, windowSeconds, maxOps, bucket);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用");
            }
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 5L));
            }
            return count <= maxOps;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用");
        }
    }
}
