package com.example.notification.service;

import com.example.notification.entity.Notification;
import com.example.notification.repo.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolates the unique-key insert in its own transaction. A concurrent
 * duplicate can therefore roll back this insert without poisoning the
 * caller's transaction before it re-reads the committed winner.
 */
@Service
public class NotificationCreateTransaction {
    private final NotificationRepository notificationRepository;
    private final NotificationEventLog events;

    public NotificationCreateTransaction(NotificationRepository notificationRepository) {
        this(notificationRepository, null);
    }

    @Autowired
    public NotificationCreateTransaction(NotificationRepository notificationRepository, NotificationEventLog events) {
        this.notificationRepository = notificationRepository;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification insert(Notification notification) {
        Notification saved = notificationRepository.save(notification);
        if (events != null) events.append(saved.getUserId(), saved.getId(), "created");
        return saved;
    }
}
