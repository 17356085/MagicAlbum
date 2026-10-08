package com.example.demo.notifications.client;

import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.service.UserSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationClient {
    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final NotificationService notificationService;
    private final UserSettingsService userSettingsService;
    private final boolean enabled;

    public NotificationClient(
            NotificationService notificationService,
            UserSettingsService userSettingsService,
            @Value("${app.notifications.client.enabled:true}") boolean enabled
    ) {
        this.notificationService = notificationService;
        this.userSettingsService = userSettingsService;
        this.enabled = enabled;
    }

    public void sendReply(Long userId, String title, String content) {
        sendReply(userId, title, content, null, null);
    }

    public void sendReply(Long userId, String title, String content, Long threadId, Long postId) {
        send(new CreateNotificationRequest(userId, "reply", title, content, threadId, postId, "post"));
    }

    public void sendMention(Long userId, String title, String content) {
        sendMention(userId, title, content, null, null);
    }

    public void sendMention(Long userId, String title, String content, Long threadId, Long postId) {
        send(new CreateNotificationRequest(userId, "mention", title, content, threadId, postId, "post"));
    }

    public void sendLike(Long userId, String title, String content) {
        sendLike(userId, title, content, null, null, null);
    }

    public void sendLike(Long userId, String title, String content, Long threadId, Long targetId, String targetType) {
        send(new CreateNotificationRequest(userId, "like", title, content, threadId, targetId, targetType));
    }

    public void sendFollow(Long userId, String title, String content, Long followerId) {
        send(new CreateNotificationRequest(userId, "follow", title, content, null, followerId, "user"));
    }

    private void send(CreateNotificationRequest request) {
        if (!enabled || request.userId() == null) {
            return;
        }
        try {
            if (!userSettingsService.shouldDeliver(request.userId(), request.type())) {
                return;
            }
            notificationService.create(
                    request.userId(),
                    request.type(),
                    request.title(),
                    request.content(),
                    request.threadId(),
                    request.targetId(),
                    request.targetType()
            );
        } catch (Exception e) {
            log.warn("notification_create_failed type={} error={}",
                    request.type(), e.getClass().getSimpleName());
            throw new IllegalStateException("通知服务暂不可用", e);
        }
    }

    private record CreateNotificationRequest(Long userId, String type, String title, String content,
                                             Long threadId, Long targetId, String targetType) {
    }
}
