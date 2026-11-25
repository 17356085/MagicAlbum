package com.example.demo.social.service;

import com.example.demo.social.repo.UserFollowRepository;
import org.springframework.stereotype.Service;

/** The social view of follow counts and the viewer's two directed relationships. */
@Service
public class FollowStatistics {
    private final UserFollowRepository follows;

    public FollowStatistics(UserFollowRepository follows) {
        this.follows = follows;
    }

    public Snapshot forUser(Long userId, Long viewerId) {
        return new Snapshot(follows.countByFollowingId(userId), follows.countByFollowerId(userId),
                isFollowing(viewerId, userId), isFollowing(userId, viewerId));
    }

    boolean isFollowing(Long follower, Long following) {
        return follower != null && following != null && !follower.equals(following)
                && follows.existsByFollowerIdAndFollowingId(follower, following);
    }

    public record Snapshot(long followers, long following, boolean followedByViewer, boolean followingViewer) {}
}
