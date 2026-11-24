package com.example.notification.messaging;

import com.example.notification.dto.CreateNotificationRequest;

import java.util.UUID;

/** Versioned comment fact carrying the already selected recipient intent. */
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

    public void validate() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION
                || !EVENT_TYPE.equals(eventType)
                || !"reply".equals(type)
                || !"post".equals(targetType)
                || !positive(commentId)
                || !positive(threadId)
                || !positive(recipientUserId)
                || !validEventId(eventId)
                || !validTitle(title)
                || !validContent(content)) {
            throw new CommentCreatedMessageValidationException();
        }
    }

    public CreateNotificationRequest toCreateRequest() {
        validate();
        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setUserId(recipientUserId);
        request.setType(type);
        request.setTitle(title);
        request.setContent(content);
        request.setThreadId(threadId);
        request.setTargetId(commentId);
        request.setTargetType(targetType);
        request.setEventId(UUID.fromString(eventId));
        return request;
    }

    private static boolean positive(long value) {
        return value > 0;
    }

    private static boolean validEventId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validTitle(String value) {
        return value != null && !value.isBlank() && value.length() <= 120;
    }

    private static boolean validContent(String value) {
        return value != null && !value.isBlank() && value.length() <= 1000;
    }
}
