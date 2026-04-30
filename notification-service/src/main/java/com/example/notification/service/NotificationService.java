package com.example.notification.service;

import com.example.notification.dto.NotificationDto;
import com.example.notification.dto.CreateNotificationRequest;

import java.util.Map;

public interface NotificationService {
    Map<String, Object> list(Long userId, String type, Boolean unread, int page, int size);

    NotificationDto markRead(Long userId, Long id);

    NotificationDto create(CreateNotificationRequest request);
}
