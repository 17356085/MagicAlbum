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
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
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
    private final Clock clock;
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();
    private final PriorityQueue<Window> localExpiry = new PriorityQueue<>(Comparator.comparingLong(window -> window.expiresAt));
    private long lastLocalSecond = Long.MIN_VALUE;

    @Autowired
    public DistributedRateLimiter(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.rate-limit.fail-closed:true}") boolean failClosed
    ) {
        this(redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(), failClosed, Clock.systemUTC());
    }

    DistributedRateLimiter(StringRedisTemplate redisTemplate, boolean failClosed) {
        this(redisTemplate, failClosed, Clock.systemUTC());
    }

    DistributedRateLimiter(StringRedisTemplate redisTemplate, boolean failClosed, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.failClosed = failClosed;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
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

        long bucket = clock.instant().getEpochSecond() / windowSeconds;
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

        return allowInMemory(key, windowSeconds, maxRequests, bucket);
    }

    private boolean handleRedisFailure() {
        if (failClosed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用");
        }
        return false;
    }

    private synchronized boolean allowInMemory(String key, int windowSeconds, int maxRequests, long bucket) {
        Instant now = clock.instant();
        long nowSecond = now.getEpochSecond();
        // An old bucket may already have been evicted; do not reopen it if the
        // system clock moves backwards while this fallback is active.
        if (nowSecond < lastLocalSecond) return false;
        lastLocalSecond = nowSecond;
        while (!localExpiry.isEmpty() && localExpiry.peek().expiresAt <= nowSecond) {
            Window expired = localExpiry.poll();
            localWindows.remove(expired.key, expired);
        }
        // A request may enter fallback after its original bucket has ended.
        // Reject it rather than recreate an already-evicted bucket repeatedly.
        long expiresAt = (bucket + 1) * (long) windowSeconds;
        if (expiresAt <= nowSecond) return false;
        Window window = localWindows.get(key);
        if (window == null) {
            window = new Window(key, expiresAt);
            localWindows.put(key, window);
            localExpiry.add(window);
        }
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
        private final String key;
        private final long expiresAt;
        private final ArrayDeque<Instant> events = new ArrayDeque<>();

        private Window(String key, long expiresAt) {
            this.key = key;
            this.expiresAt = expiresAt;
        }
    }
}
