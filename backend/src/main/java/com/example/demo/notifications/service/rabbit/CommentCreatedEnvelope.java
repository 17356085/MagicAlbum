package com.example.demo.notifications.service.rabbit;

import com.example.demo.notifications.outbox.NotificationOutboxService;

import java.util.Objects;

/**
 * Versioned domain-fact plus recipient notification intent.
 *
 * <p>The comment identity is the outbox target id. The title and content are
 * the already-normalized notification intent and are not reconstructed by the
 * receiver.</p>
 */
public record CommentCreatedEnvelope(
        String eventId,
        int schemaVersion,
        String eventType,
        long commentId,
        long threadId,
        long recipientUserId,
        String type,
        String targetType,
        String title,
        String content
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "COMMENT_CREATED";
    public static final String NOTIFICATION_TYPE = "reply";
    public static final String TARGET_TYPE = "post";

    public static boolean supports(NotificationOutboxService.Delivery delivery) {
        return delivery != null
                && NOTIFICATION_TYPE.equalsIgnoreCase(normalize(delivery.type()))
                && TARGET_TYPE.equalsIgnoreCase(normalize(delivery.targetType()))
                && positive(delivery.recipientUserId())
                && positive(delivery.threadId())
                && positive(delivery.targetId())
                && hasText(delivery.eventId())
                && hasText(delivery.title())
                && hasText(delivery.content());
    }

    public static CommentCreatedEnvelope from(NotificationOutboxService.Delivery delivery) {
        if (!supports(delivery)) {
            throw new CommentCreatedDeliveryException(CommentCreatedDeliveryException.Code.INVALID_INTENT);
        }
        return new CommentCreatedEnvelope(
                delivery.eventId(),
                CURRENT_SCHEMA_VERSION,
                EVENT_TYPE,
                delivery.targetId(),
                delivery.threadId(),
                delivery.recipientUserId(),
                NOTIFICATION_TYPE,
                TARGET_TYPE,
                delivery.title().trim(),
                delivery.content().trim()
        );
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
