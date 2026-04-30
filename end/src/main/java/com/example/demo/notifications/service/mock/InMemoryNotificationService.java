package com.example.demo.notifications.service.mock;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 开发期通知占位实现。
 * 当前仅提供内存种子数据，后续可由 persistent 包中的正式实现替换。
 */
@Service
public class InMemoryNotificationService implements NotificationService {
    private final ConcurrentHashMap<Long, List<NotificationDto>> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1000);

    private List<NotificationDto> ensureSeed(Long userId) {
        return store.computeIfAbsent(userId, k -> new ArrayList<>());
    }

    @Override
    public Map<String, Object> list(Long userId, String type, Boolean unread, int page, int size) {
        List<NotificationDto> all = new ArrayList<>(ensureSeed(userId));
        if (type != null && !type.isBlank()) {
            String t = type.toLowerCase();
            all = all.stream().filter(n -> t.equals(String.valueOf(n.getType()).toLowerCase())).collect(Collectors.toList());
        }
        if (unread != null) {
            all = all.stream().filter(n -> unread ? !n.isRead() : true).collect(Collectors.toList());
        }
        int total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<NotificationDto> items = from >= total ? List.of() : all.subList(from, to);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("total", total);
        return body;
    }

    @Override
    public NotificationDto markRead(Long userId, Long id) {
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
