package com.example.demo.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window limiter used at unauthenticated boundaries.
 *
 * Redis is the production source of truth. Local memory is available only
 * when explicitly allowed by the local/test profile, so a missing Redis
 * connection cannot silently weaken production abuse protection.
 */
@Component
public class DistributedRateLimiter {
    private final StringRedisTemplate redisTemplate;
    private final boolean failClosed;
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();

    @Autowired
    public DistributedRateLimiter(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.rate-limit.fail-closed:true}") boolean failClosed
    ) {
        this.redisTemplate = redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
        this.failClosed = failClosed;
    }

    DistributedRateLimiter(StringRedisTemplate redisTemplate, boolean failClosed) {
        this.redisTemplate = redisTemplate;
        this.failClosed = failClosed;
    }

    public void enforce(String dimension, String value, int windowSeconds, int maxRequests) {
        if (!allow(dimension, value, windowSeconds, maxRequests)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
    }

    public boolean allow(String dimension, String value, int windowSeconds, int maxRequests) {
        if (dimension == null || dimension.isBlank() || value == null || value.isBlank()
                || windowSeconds <= 0 || maxRequests <= 0) {
            return false;
        }

        long bucket = Instant.now().getEpochSecond() / windowSeconds;
        String key = RedisKeys.rateLimit(dimension, windowSeconds, maxRequests, bucket, hash(value));
        if (redisTemplate != null) {
            try {
                Long count = redisTemplate.opsForValue().increment(key);
                if (count == null) {
                    return handleRedisFailure();
                }
                if (count == 1L) {
                    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 5L));
                }
                return count <= maxRequests;
            } catch (Exception ex) {
                if (failClosed) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用");
                }
            }
        } else if (failClosed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务未配置");
        }

        return allowInMemory(key, windowSeconds, maxRequests);
    }

    private boolean handleRedisFailure() {
        if (failClosed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用");
        }
        return false;
    }

    private synchronized boolean allowInMemory(String key, int windowSeconds, int maxRequests) {
        Window window = localWindows.computeIfAbsent(key, ignored -> new Window());
        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(windowSeconds);
        while (!window.events.isEmpty() && window.events.peekFirst().isBefore(threshold)) {
            window.events.pollFirst();
        }
        if (window.events.size() >= maxRequests) {
            return false;
        }
        window.events.addLast(now);
        return true;
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                encoded.append(String.format("%02x", item));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static final class Window {
        private final ArrayDeque<Instant> events = new ArrayDeque<>();
    }
}
