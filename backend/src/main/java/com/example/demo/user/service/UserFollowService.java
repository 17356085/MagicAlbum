package com.example.demo.user.service;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.user.dto.ProfileDto;
import com.example.demo.user.dto.UserFollowResponse;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserFollow;
import com.example.demo.user.repo.UserFollowRepository;
import com.example.demo.user.repo.UserRepository;
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
    private final UserRepository userRepository;
    private final UserProfileService userProfileService;
    private final NotificationClient notificationClient;

    public UserFollowService(UserFollowRepository userFollowRepository,
                             UserRepository userRepository,
                             UserProfileService userProfileService,
                             ObjectProvider<NotificationClient> notificationClientProvider) {
        this.userFollowRepository = userFollowRepository;
        this.userRepository = userRepository;
        this.userProfileService = userProfileService;
        this.notificationClient = notificationClientProvider == null ? null : notificationClientProvider.getIfAvailable();
    }

    @Transactional
    public UserFollowResponse follow(Long currentUserId, Long targetUserId) {
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
        validateTarget(currentUserId, targetUserId);
        if (currentUserId.equals(targetUserId)) {
            return status(currentUserId, targetUserId);
        }
        if (userFollowRepository.existsByFollowerIdAndFollowingId(currentUserId, targetUserId)) {
            userFollowRepository.deleteByFollowerIdAndFollowingId(currentUserId, targetUserId);
        }
        return status(currentUserId, targetUserId);
    }

    @Transactional(readOnly = true)
    public UserFollowResponse status(Long currentUserId, Long targetUserId) {
        validateTarget(currentUserId, targetUserId);
        boolean followedByMe = currentUserId != null
                && !currentUserId.equals(targetUserId)
                && userFollowRepository.existsByFollowerIdAndFollowingId(currentUserId, targetUserId);
        boolean followingMe = currentUserId != null
                && !currentUserId.equals(targetUserId)
                && userFollowRepository.existsByFollowerIdAndFollowingId(targetUserId, currentUserId);
        return new UserFollowResponse(
                targetUserId,
                followedByMe,
                userFollowRepository.countByFollowingId(targetUserId),
                userFollowRepository.countByFollowerId(targetUserId),
                followingMe,
                followedByMe
        );
    }

    @Transactional(readOnly = true)
    public Page<UserSummaryDto> listFollowers(Long userId, int page, int size) {
        ensureUserExists(userId);
        int limit = Math.min(Math.max(size, 1), 50);
        int pageIndex = Math.max(page - 1, 0);
        Page<Long> ids = userFollowRepository.findFollowerIds(userId, PageRequest.of(pageIndex, limit));
        return toUserSummaryPage(ids);
    }

    @Transactional(readOnly = true)
    public Page<UserSummaryDto> listFollowing(Long userId, int page, int size) {
        ensureUserExists(userId);
        int limit = Math.min(Math.max(size, 1), 50);
        int pageIndex = Math.max(page - 1, 0);
        Page<Long> ids = userFollowRepository.findFollowingIds(userId, PageRequest.of(pageIndex, limit));
        return toUserSummaryPage(ids);
    }

    long countFollowers(Long userId) {
        if (userId == null) {
            return 0;
        }
        return userFollowRepository.countByFollowingId(userId);
    }

    long countFollowing(Long userId) {
        if (userId == null) {
            return 0;
        }
        return userFollowRepository.countByFollowerId(userId);
    }

    boolean isFollowing(Long followerId, Long followingId) {
        if (followerId == null || followingId == null || followerId.equals(followingId)) {
            return false;
        }
        return userFollowRepository.existsByFollowerIdAndFollowingId(followerId, followingId);
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
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
    }

    private Page<UserSummaryDto> toUserSummaryPage(Page<Long> ids) {
        List<Long> userIds = ids.getContent();
        List<User> users = userRepository.findAllById(userIds);
        Map<Long, ProfileDto> profiles = userProfileService.getProfiles(userIds);
        Map<Long, User> userMap = users.stream().collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        List<UserSummaryDto> items = userIds.stream()
                .map(userMap::get)
                .filter(java.util.Objects::nonNull)
                .map(user -> toSummary(user, profiles.get(user.getId())))
                .toList();
        return new PageImpl<>(items, ids.getPageable(), ids.getTotalElements());
    }

    private UserSummaryDto toSummary(User user, ProfileDto profile) {
        UserSummaryDto dto = new UserSummaryDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setCreatedAt(user.getCreatedAt());
        if (profile != null) {
            dto.setNickname(profile.getNickname());
            dto.setAvatarUrl(profile.getAvatarUrl());
        }
        return dto;
    }

    private void sendFollowNotification(Long followerId, Long targetUserId) {
        if (notificationClient == null) {
            return;
        }
        String followerName = userRepository.findById(followerId)
                .map(user -> user.getUsername() == null || user.getUsername().isBlank() ? "有人" : user.getUsername())
                .orElse("有人");
        notificationClient.sendFollow(
                targetUserId,
                "你有新的关注者",
                followerName + " 关注了你",
                followerId
        );
    }
}
