package com.example.demo.posts.dto;

public class PostLikeResponse {
    private Long postId;
    private boolean liked;
    private long likeCount;

    public PostLikeResponse() {
    }

    public PostLikeResponse(Long postId, boolean liked, long likeCount) {
        this.postId = postId;
        this.liked = liked;
        this.likeCount = likeCount;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
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
