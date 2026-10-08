package com.example.demo.threads.dto;

public interface ThreadFeedView extends ThreadQueryView {
    Long getReplyCount();
    Long getLikeCount();
}
