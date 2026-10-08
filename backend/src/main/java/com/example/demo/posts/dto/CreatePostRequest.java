package com.example.demo.posts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreatePostRequest {
    @NotBlank
    @Size(min = 1, max = 3000)
    private String contentMd;

    // 可选：回复某评论（允许多层回复）
    private Long replyToPostId;

    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public Long getReplyToPostId() { return replyToPostId; }
    public void setReplyToPostId(Long replyToPostId) { this.replyToPostId = replyToPostId; }
}