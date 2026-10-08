package com.example.demo.notifications.service;

import com.example.demo.notifications.dto.NotificationDto;

import java.util.Map;

/**
 * 通知模块应用层契约。
 * Controller 仅依赖此接口，具体可由 mock 或持久化实现承载。
 */
public interface NotificationService {
    Map<String, Object> list(Long userId, String type, Boolean unread, int page, int size);

    NotificationDto markRead(Long userId, Long id);

    NotificationDto create(Long userId, String type, String title, String content,
                           Long threadId, Long targetId, String targetType);
}
