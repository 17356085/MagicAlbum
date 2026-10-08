package com.example.demo.posts.dto;

import java.time.Instant;

public interface PostQueryView {
    Long getId();
    Long getThreadId();
    String getThreadTitle();
    Long getAuthorId();
    String getAuthorUsername();
    String getAuthorNickname();
    String getAuthorAvatarUrl();
    String getContent();
    Long getReplyToPostId();
    Long getParentAuthorId();
    String getParentAuthorUsername();
    String getParentAuthorNickname();
    Instant getCreatedAt();
    Instant getUpdatedAt();
}
