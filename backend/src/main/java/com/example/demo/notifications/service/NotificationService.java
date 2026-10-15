package com.example.demo.notifications.service;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.dto.NotificationPageResponse;

import java.util.UUID;

/**
 * 通知模块应用层契约。
 * Controller 仅依赖此接口，具体可由 mock 或持久化实现承载。
 */
public interface NotificationService {
    NotificationPageResponse list(Long userId, String authorization, String type, Boolean unread, int page, int size);

    NotificationDto markRead(Long userId, String authorization, Long id);

    NotificationDto create(Long userId, String type, String title, String content,
                           Long threadId, Long targetId, String targetType);

    /**
     * Reliable delivery overload. The default keeps mock and legacy direct
     * callers source-compatible while remote delivery carries the event key.
     */
    default NotificationDto create(Long userId, String type, String title, String content,
                                   Long threadId, Long targetId, String targetType, UUID eventId) {
        return create(userId, type, title, content, threadId, targetId, targetType);
    }
}
