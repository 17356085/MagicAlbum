package com.example.demo.notifications.outbox;

public enum NotificationOutboxStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED,
    SUPPRESSED
}
