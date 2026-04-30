package com.example.demo.threads.dto;

public class ThreadLikeResponse {
    private Long threadId;
    private boolean liked;
    private long likeCount;

    public ThreadLikeResponse() {
    }

    public ThreadLikeResponse(Long threadId, boolean liked, long likeCount) {
        this.threadId = threadId;
        this.liked = liked;
        this.likeCount = likeCount;
    }

    public Long getThreadId() {
        return threadId;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
    }

    public boolean isLiked() {
        return liked;
    }

    public void setLiked(boolean liked) {
        this.liked = liked;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(long likeCount) {
        this.likeCount = likeCount;
    }
}
