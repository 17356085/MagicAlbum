package com.example.demo.notifications.dto;

import java.time.Instant;

public class NotificationDto {
    private Long id;
    private String type; // reply | mention | like | system
    private String title;
    private String content;
    private boolean read;
    private Instant createdAt;
    private Long threadId;
    private Long targetId;
    private String targetType;
    private String link;

    public NotificationDto() {}

    public NotificationDto(Long id, String type, String title, String content, boolean read, Instant createdAt) {
        this(id, type, title, content, read, createdAt, null, null, null, null);
    }

    public NotificationDto(Long id, String type, String title, String content, boolean read, Instant createdAt,
                           Long threadId, Long targetId, String targetType) {
        this(id, type, title, content, read, createdAt, threadId, targetId, targetType, buildLink(threadId, targetId, targetType));
    }

    public NotificationDto(Long id, String type, String title, String content, boolean read, Instant createdAt,
                           Long threadId, Long targetId, String targetType, String link) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.content = content;
        this.read = read;
        this.createdAt = createdAt;
        this.threadId = threadId;
        this.targetId = targetId;
        this.targetType = targetType;
        this.link = link;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getThreadId() { return threadId; }
    public void setThreadId(Long threadId) { this.threadId = threadId; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    private static String buildLink(Long threadId, Long targetId, String targetType) {
        if ("user".equals(targetType) && targetId != null) {
            return "/users/" + targetId;
        }
        if (threadId == null) {
            return null;
        }
        if ("post".equals(targetType) && targetId != null) {
            return "/threads/" + threadId + "#post-" + targetId;
        }
        return "/threads/" + threadId;
    }

    public void normalizeLink() {
        if (link != null && !link.isBlank()) {
            return;
        }
        if ("user".equals(targetType) && targetId != null) {
            link = "/users/" + targetId;
            return;
        }
        link = buildLink(threadId, targetId, targetType);
    }
}
