package com.example.demo.user.dto;

public class UserFollowResponse {
    private Long userId;
    private boolean following;
    private long followerCount;
    private long followingCount;
    private boolean followingMe;
    private boolean followedByMe;

    public UserFollowResponse() {
    }

    public UserFollowResponse(Long userId, boolean following, long followerCount, long followingCount,
                              boolean followingMe, boolean followedByMe) {
        this.userId = userId;
        this.following = following;
        this.followerCount = followerCount;
        this.followingCount = followingCount;
        this.followingMe = followingMe;
        this.followedByMe = followedByMe;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public boolean isFollowing() { return following; }
    public void setFollowing(boolean following) { this.following = following; }

    public long getFollowerCount() { return followerCount; }
    public void setFollowerCount(long followerCount) { this.followerCount = followerCount; }

    public long getFollowingCount() { return followingCount; }
    public void setFollowingCount(long followingCount) { this.followingCount = followingCount; }

    public boolean isFollowingMe() { return followingMe; }
    public void setFollowingMe(boolean followingMe) { this.followingMe = followingMe; }

    public boolean isFollowedByMe() { return followedByMe; }
    public void setFollowedByMe(boolean followedByMe) { this.followedByMe = followedByMe; }
}
