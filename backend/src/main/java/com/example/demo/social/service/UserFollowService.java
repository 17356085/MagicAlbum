package com.example.demo.social.service;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.social.entity.UserFollow;
import com.example.demo.social.repo.UserFollowRepository;
import com.example.demo.user.dto.UserFollowResponse;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.service.RelationshipAccountLock;
import com.example.demo.user.service.UserProfileService;
import com.example.demo.user.service.UserReadService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class UserFollowService {
    private final UserFollowRepository userFollowRepository;
    private final RelationshipAccountLock accountLock;
    private final UserReadService accounts;
    private final UserProfileService userProfileService;
    private final FollowStatistics statistics;
    private final NotificationClient notificationClient;

    public UserFollowService(UserFollowRepository userFollowRepository,
                             RelationshipAccountLock accountLock,
                             UserReadService accounts,
                             UserProfileService userProfileService,
                             FollowStatistics statistics,
                             ObjectProvider<NotificationClient> notificationClientProvider) {
        this.userFollowRepository = userFollowRepository;
        this.accountLock = accountLock;
        this.accounts = accounts;
        this.userProfileService = userProfileService;
        this.statistics = statistics;
        this.notificationClient = notificationClientProvider == null ? null : notificationClientProvider.getIfAvailable();
    }

    @Transactional
    public UserFollowResponse follow(Long currentUserId, Long targetUserId) {
        lockPair(currentUserId, targetUserId);
        validateFollowRequest(currentUserId, targetUserId);
        boolean alreadyFollowing = userFollowRepository.existsByFollowerIdAndFollowingId(currentUserId, targetUserId);
        if (!alreadyFollowing) {
            UserFollow follow = new UserFollow();
            follow.setFollowerId(currentUserId);
            follow.setFollowingId(targetUserId);
            userFollowRepository.save(follow);
            sendFollowNotification(currentUserId, targetUserId);
        }
        return status(currentUserId, targetUserId);
    }

    @Transactional
    public UserFollowResponse unfollow(Long currentUserId, Long targetUserId) {
        lockPair(currentUserId, targetUserId);
        validateTarget(currentUserId, targetUserId);
        if (currentUserId.equals(targetUserId)) {
            return status(currentUserId, targetUserId);
        }
        removeOutgoingFollow(currentUserId, targetUserId);
        return status(currentUserId, targetUserId);
    }

    /** Caller holds the relationship lock for both accounts. */
    void removeOutgoingFollow(Long currentUserId, Long targetUserId) {
        if (statistics.isFollowing(currentUserId, targetUserId)) {
            userFollowRepository.deleteByFollowerIdAndFollowingId(currentUserId, targetUserId);
        }
    }

    @Transactional(readOnly = true)
    public UserFollowResponse status(Long currentUserId, Long targetUserId) {
        validateTarget(currentUserId, targetUserId);
        FollowStatistics.Snapshot state = statistics.forUser(targetUserId, currentUserId);
        return new UserFollowResponse(
                targetUserId,
                state.followedByViewer(),
                state.followers(),
                state.following(),
                state.followingViewer(),
                state.followedByViewer()
        );
    }

    @Transactional(readOnly = true)
    public Page<UserSummaryDto> listFollowers(Long userId, int page, int size) {
        ensureUserExists(userId);
        int limit = Math.min(Math.max(size, 1), 50);
        int pageIndex = Math.max(page, 1) - 1;
        Page<Long> ids = userFollowRepository.findFollowerIds(userId, PageRequest.of(pageIndex, limit));
        return toUserSummaryPage(ids);
    }

    @Transactional(readOnly = true)
    public Page<UserSummaryDto> listFollowing(Long userId, int page, int size) {
        ensureUserExists(userId);
        int limit = Math.min(Math.max(size, 1), 50);
        int pageIndex = Math.max(page, 1) - 1;
        Page<Long> ids = userFollowRepository.findFollowingIds(userId, PageRequest.of(pageIndex, limit));
        return toUserSummaryPage(ids);
    }

    @Transactional(readOnly = true)
    public Page<UserSummaryDto> listMutualFriends(Long userId, List<Long> excluded, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 50);
        return toUserSummaryPage(userFollowRepository.findMutualIds(userId,
                excluded.isEmpty() ? List.of(0L) : excluded, PageRequest.of(Math.max(page, 1) - 1, limit)));
    }

    @Transactional(readOnly = true)
    public boolean areMutual(Long first, Long second) {
        return statistics.isFollowing(first, second) && statistics.isFollowing(second, first);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockForMessaging(Long first, Long second) {
        lockPair(first, second);
    }

    /**
     * Locks a relationship participant set in one stable order. Follow,
     * block and chat group creation share this boundary so opposite requests
     * cannot acquire the same user rows in different orders.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockUsers(java.util.Collection<Long> ids) {
        accountLock.lockParticipants(ids);
    }

    private void lockPair(Long first, Long second) {
        if (first == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        if (second == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        // A stable order also covers simultaneous A->B and B->A requests.
        lockUsers(java.util.List.of(first, second));
    }

    private void validateFollowRequest(Long currentUserId, Long targetUserId) {
        validateTarget(currentUserId, targetUserId);
        if (currentUserId.equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能关注自己");
        }
    }

    private void validateTarget(Long currentUserId, Long targetUserId) {
        if (currentUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        ensureUserExists(targetUserId);
    }

    private void ensureUserExists(Long userId) {
        if (!accounts.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
    }

    private Page<UserSummaryDto> toUserSummaryPage(Page<Long> ids) {
        List<Long> userIds = ids.getContent();
        Map<Long, UserSummaryDto> summaries = userProfileService.getUserSummaries(userIds);
        List<UserSummaryDto> items = userIds.stream()
                .map(summaries::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new PageImpl<>(items, ids.getPageable(), ids.getTotalElements());
    }

    private void sendFollowNotification(Long followerId, Long targetUserId) {
        if (notificationClient == null) {
            return;
        }
        String followerName = accounts.displayNameById(followerId)
                .filter(name -> !name.isBlank())
                .orElse("有人");
        notificationClient.sendFollow(
                targetUserId,
                "你有新的关注者",
                followerName + " 关注了你",
                followerId
        );
    }
}
