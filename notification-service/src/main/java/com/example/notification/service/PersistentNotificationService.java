package com.example.notification.service;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import com.example.notification.entity.Notification;
import com.example.notification.repo.NotificationRepository;
import com.example.notification.repo.NotificationRepository.NotificationPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class PersistentNotificationService implements NotificationService {
    private final NotificationRepository notificationRepository;

    public PersistentNotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> list(Long userId, String type, Boolean unread, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        int offset = (normalizedPage - 1) * normalizedSize;

        NotificationPage notifications = notificationRepository.find(userId, type, unread, offset, normalizedSize);
        Map<String, Object> body = new HashMap<>();
        body.put("items", notifications.items().stream().map(this::toDto).toList());
        body.put("page", normalizedPage);
        body.put("size", normalizedSize);
        body.put("total", notifications.total());
        body.put("source", "bluealbum-notification-service");
        return body;
    }

    @Override
    @Transactional
    public NotificationDto markRead(Long userId, Long id) {
        return notificationRepository.findByIdAndUserId(id, userId)
                .map(notification -> {
                    notificationRepository.markRead(id, userId);
                    notification.setRead(true);
                    return toDto(notification);
                })
                .orElse(null);
    }

    @Override
    @Transactional
    public NotificationDto create(CreateNotificationRequest request) {
        Notification notification = new Notification();
        notification.setUserId(request.getUserId());
        notification.setType(request.getType().trim());
        notification.setTitle(request.getTitle().trim());
        notification.setContent(request.getContent().trim());
        notification.setRead(false);
        return toDto(notificationRepository.save(notification));
    }

    private NotificationDto toDto(Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
