package com.example.demo.notifications.service;

import com.example.demo.notifications.dto.NotificationDto;

import java.util.Objects;

/** Confirms the immutable fields exposed by the receiver's creation response. */
public final class NotificationCreationAcknowledgement {
    private NotificationCreationAcknowledgement() {
    }

    public static NotificationDto requireCreated(NotificationDto response, String type, String title, String content,
                                                 Long threadId, Long targetId, String targetType) {
        if (response == null || response.getId() == null || response.getId() <= 0
                || !matchesText(response.getType(), type)
                || !matchesText(response.getTitle(), title)
                || !matchesText(response.getContent(), content)
                || !Objects.equals(response.getThreadId(), threadId)
                || !Objects.equals(response.getTargetId(), targetId)
                || !Objects.equals(response.getTargetType(), trim(targetType))) {
            throw new IllegalStateException("Unconfirmed persisted notification acknowledgement");
        }
        // A replay can already be read and have an older creation timestamp.
        return response;
    }

    private static boolean matchesText(String actual, String expected) {
        return actual != null && !actual.isBlank() && actual.equals(trim(expected));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
