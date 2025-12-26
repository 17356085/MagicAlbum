package com.example.demo.notifications.dto;

import java.time.Instant;

public class NotificationDto {
    private Long id;
    private String type; // reply | mention | like | system
    private String title;
    private String content;
    private boolean read;
    private Instant createdAt;

    public NotificationDto() {}

    public NotificationDto(Long id, String type, String title, String content, boolean read, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.content = content;
        this.read = read;
        this.createdAt = createdAt;
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
}