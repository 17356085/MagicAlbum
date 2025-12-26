package com.example.demo.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单用户级限速：某时间窗口最多允许 N 次。
 * 非分布式实现，适合单实例开发与课程项目。
 */
@Component
public class SimpleRateLimiter {
    private final Map<Long, ArrayDeque<Instant>> buckets = new ConcurrentHashMap<>();

    /**
     * 校验并记录一次操作；返回是否允许。
     * @param userId 用户ID
     * @param windowSeconds 时间窗口（秒）
     * @param maxOps 最大次数
     * @return true 表示允许
     */
    public synchronized boolean allow(Long userId, int windowSeconds, int maxOps) {
        if (userId == null) return false;
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