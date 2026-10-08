package com.example.notification.dto;

import java.time.Instant;

public class NotificationDto {
    private Long id;
    private String type;
    private String title;
    private String content;
    private Long threadId;
    private Long targetId;
    private String targetType;
    private String link;
    private boolean read;
    private Instant createdAt;

    public NotificationDto() {
    }

    public NotificationDto(Long id, String type, String title, String content, boolean read, Instant createdAt) {
        this(id, type, title, content, read, createdAt, null, null, null);
    }

    public NotificationDto(
            Long id,
            String type,
            String title,
            String content,
            boolean read,
            Instant createdAt,
            Long threadId,
            Long targetId,
            String targetType
    ) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.content = content;
        this.threadId = threadId;
        this.targetId = targetId;
        this.targetType = targetType;
        this.link = buildLink(threadId, targetId, targetType);
        this.read = read;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getThreadId() {
        return threadId;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
        this.link = buildLink(this.threadId, this.targetId, this.targetType);
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
        this.link = buildLink(this.threadId, this.targetId, this.targetType);
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
        this.link = buildLink(this.threadId, this.targetId, this.targetType);
    }

    public String getLink() {
        return link;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    private static String buildLink(Long threadId, Long targetId, String targetType) {
        if ("user".equals(targetType) && targetId != null) {
            return "/users/" + targetId;
        }
        if ("post".equals(targetType) && threadId != null && targetId != null) {
            return "/threads/" + threadId + "#post-" + targetId;
        }
        if ("thread".equals(targetType) && threadId != null) {
            return "/threads/" + threadId;
        }
        return null;
    }
}
