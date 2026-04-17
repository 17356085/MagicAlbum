package com.example.demo.threads.dto;

import java.time.Instant;

public interface ThreadQueryView {
    Long getId();
    Long getSectionId();
    String getSectionName();
    Long getAuthorId();
    String getAuthorUsername();
    String getAuthorNickname();
    String getAuthorAvatar();
    String getTitle();
    String getContent();
    String getStatus();
    Instant getCreatedAt();
    Instant getUpdatedAt();
}
