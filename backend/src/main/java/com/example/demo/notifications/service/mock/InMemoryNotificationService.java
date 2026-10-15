package com.example.demo.notifications.service.mock;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.dto.NotificationPageResponse;
import com.example.demo.notifications.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 开发期通知占位实现。
 * 当前仅提供内存种子数据，后续可由 persistent 包中的正式实现替换。
 */
@Service
@ConditionalOnProperty(name = "app.notifications.client.mode", havingValue = "mock")
public class InMemoryNotificationService implements NotificationService {
    private final ConcurrentHashMap<Long, List<NotificationDto>> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1000);

    private List<NotificationDto> ensureSeed(Long userId) {
        return store.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
    }

    @Override
    public NotificationPageResponse list(Long userId, String authorization, String type, Boolean unread, int page, int size) {
        List<NotificationDto> all = new ArrayList<>(ensureSeed(userId));
        if (type != null && !type.isBlank()) {
            String t = type.toLowerCase(Locale.ROOT);
            all = all.stream().filter(n -> t.equals(String.valueOf(n.getType()).toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (unread != null) {
            all = all.stream().filter(n -> unread ? !n.isRead() : true).collect(Collectors.toList());
        }
        int total = all.size();
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        long offset = ((long) normalizedPage - 1) * normalizedSize;
        List<NotificationDto> items = offset >= total ? List.of()
                : all.subList((int) offset, (int) Math.min(total, offset + normalizedSize));

        return new NotificationPageResponse(items, normalizedPage, normalizedSize, total, null);
    }

    @Override
    public NotificationDto markRead(Long userId, String authorization, Long id) {
        List<NotificationDto> list = ensureSeed(userId);
        for (NotificationDto n : list) {
            if (id != null && id.equals(n.getId())) {
                n.setRead(true);
                return n;
            }
        }
        return null;
    }

    @Override
    public NotificationDto create(Long userId, String type, String title, String content,
                                  Long threadId, Long targetId, String targetType) {
        if (userId == null) {
            return null;
        }
        NotificationDto notification = new NotificationDto(
                idSequence.incrementAndGet(),
                normalize(type, "system"),
                normalize(title, "你有一条新通知"),
                normalize(content, ""),
                false,
                Instant.now(),
                threadId,
                targetId,
                normalize(targetType, "")
        );
        ensureSeed(userId).add(0, notification);
        return notification;
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
