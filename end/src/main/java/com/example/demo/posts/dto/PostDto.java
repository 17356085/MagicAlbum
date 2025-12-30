package com.example.demo.posts.dto;

import java.time.Instant;

public class PostDto {
    private Long id;
    private Long threadId;
    private String threadTitle;
    private Long authorId;
    private String authorUsername;
    private String authorNickname;
    private String authorAvatarUrl;
    private String content;
    private Long replyToPostId;
    private Long parentAuthorId;
    private String parentAuthorUsername;
    private String parentAuthorNickname;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getThreadId() { return threadId; }
    public void setThreadId(Long threadId) { this.threadId = threadId; }
    public String getThreadTitle() { return threadTitle; }
    public void setThreadTitle(String threadTitle) { this.threadTitle = threadTitle; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public String getAuthorUsername() { return authorUsername; }
    public void setAuthorUsername(String authorUsername) { this.authorUsername = authorUsername; }
    public String getAuthorNickname() { return authorNickname; }
    public void setAuthorNickname(String authorNickname) { this.authorNickname = authorNickname; }
    public String getAuthorAvatarUrl() { return authorAvatarUrl; }
    public void setAuthorAvatarUrl(String authorAvatarUrl) { this.authorAvatarUrl = authorAvatarUrl; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getReplyToPostId() { return replyToPostId; }
    public void setReplyToPostId(Long replyToPostId) { this.replyToPostId = replyToPostId; }
    public Long getParentAuthorId() { return parentAuthorId; }
    public void setParentAuthorId(Long parentAuthorId) { this.parentAuthorId = parentAuthorId; }
    public String getParentAuthorUsername() { return parentAuthorUsername; }
    public void setParentAuthorUsername(String parentAuthorUsername) { this.parentAuthorUsername = parentAuthorUsername; }
    public String getParentAuthorNickname() { return parentAuthorNickname; }
    public void setParentAuthorNickname(String parentAuthorNickname) { this.parentAuthorNickname = parentAuthorNickname; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}