package com.example.notification.service;

import com.example.notification.dto.CreateNotificationRequest;
import com.example.notification.dto.NotificationDto;
import com.example.notification.dto.NotificationPageResponse;
import com.example.notification.entity.Notification;
import com.example.notification.repo.NotificationRepository;
import com.example.notification.repo.NotificationRepository.NotificationPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersistentNotificationService implements NotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationCreateTransaction notificationCreateTransaction;
    private final NotificationEventLog events;

    @Autowired
    public PersistentNotificationService(NotificationRepository notificationRepository,
                                         NotificationCreateTransaction notificationCreateTransaction,
                                         NotificationEventLog events) {
        this.notificationRepository = notificationRepository;
        this.notificationCreateTransaction = notificationCreateTransaction;
        this.events = events;
    }

    public PersistentNotificationService(NotificationRepository repository, NotificationCreateTransaction transaction) {
        this(repository, transaction, null);
    }

    public PersistentNotificationService(NotificationRepository notificationRepository) {
        this(notificationRepository, new NotificationCreateTransaction(notificationRepository));
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPageResponse list(Long userId, String type, Boolean unread, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        long offset = ((long) normalizedPage - 1L) * normalizedSize;

        NotificationPage notifications = notificationRepository.find(userId, type, unread, offset, normalizedSize);
        return new NotificationPageResponse(
                notifications.items().stream().map(this::toDto).toList(),
                normalizedPage,
                normalizedSize,
                notifications.total(),
                "bluealbum-notification-service"
        );
    }

    @Override
    @Transactional
    public NotificationDto markRead(Long userId, Long id) {
        return notificationRepository.findByIdAndUserId(id, userId)
                .map(notification -> {
                    if (!notification.isRead()) {
                        boolean changed = notificationRepository.markRead(id, userId);
                        if (changed && events != null) events.append(userId, id, "read");
                    }
                    notification.setRead(true);
                    return toDto(notification);
                })
                .orElse(null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NotificationDto create(CreateNotificationRequest request) {
        String idempotencyKey = request.getEventId() == null ? null : request.getEventId().toString();
        if (idempotencyKey != null) {
            Notification existing = notificationRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                return toDto(requireSamePayload(existing, request));
            }
        }

        Notification notification = new Notification();
        notification.setUserId(request.getUserId());
        notification.setType(request.getType().trim());
        notification.setTitle(request.getTitle().trim());
        notification.setContent(request.getContent().trim());
        notification.setThreadId(request.getThreadId());
        notification.setTargetId(request.getTargetId());
        notification.setTargetType(request.getTargetType() == null ? null : request.getTargetType().trim());
        notification.setIdempotencyKey(idempotencyKey);
        notification.setRead(false);
        try {
            return toDto(notificationCreateTransaction.insert(notification));
        } catch (DataIntegrityViolationException duplicate) {
            // The insert transaction is already rolled back. Re-read from a
            // fresh JDBC operation before deciding whether this is a replay.
            if (idempotencyKey == null) {
                throw duplicate;
            }
            Notification existing = notificationRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> duplicate);
            return toDto(requireSamePayload(existing, request));
        }
    }

    private Notification requireSamePayload(Notification existing, CreateNotificationRequest request) {
        String requestedTargetType = request.getTargetType() == null ? null : request.getTargetType().trim();
        if (!java.util.Objects.equals(existing.getUserId(), request.getUserId())
                || !java.util.Objects.equals(existing.getType(), request.getType().trim())
                || !java.util.Objects.equals(existing.getTitle(), request.getTitle().trim())
                || !java.util.Objects.equals(existing.getContent(), request.getContent().trim())
                || !java.util.Objects.equals(existing.getThreadId(), request.getThreadId())
                || !java.util.Objects.equals(existing.getTargetId(), request.getTargetId())
                || !java.util.Objects.equals(existing.getTargetType(), requestedTargetType)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通知事件幂等键对应的负载不一致");
        }
        return existing;
    }

    private NotificationDto toDto(Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getThreadId(),
                notification.getTargetId(),
                notification.getTargetType()
        );
    }
}
