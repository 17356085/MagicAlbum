package com.example.demo.user.service;

import com.example.demo.user.dto.UserSettingsDto;
import com.example.demo.user.entity.UserNotificationSettings;
import com.example.demo.user.repo.UserNotificationSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserSettingsService {
    private final UserNotificationSettingsRepository repository;

    public UserSettingsService(UserNotificationSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public UserSettingsDto getSettings(Long userId) {
        return repository.findById(userId).map(this::toDto).orElseGet(UserSettingsDto::new);
    }

    @Transactional
    public UserSettingsDto updateSettings(Long userId, UserSettingsDto payload) {
        if (payload == null || payload.getInApp() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站内通知设置不能为空");
        }
        if (payload.getEmail() != null && payload.getEmail().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮件通知暂未开放");
        }
        if (!payload.getInApp().isSystem()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "系统通知暂不可关闭");
        }
        UserNotificationSettings entity = repository.findById(userId).orElseGet(UserNotificationSettings::new);
        entity.setUserId(userId);
        entity.setReplyEnabled(payload.getInApp().isReply());
        entity.setMentionEnabled(payload.getInApp().isMention());
        entity.setLikeEnabled(payload.getInApp().isLike());
        entity.setFollowEnabled(payload.getInApp().isFollow());
        return toDto(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public boolean shouldDeliver(Long userId, String type) {
        if (userId == null) return false;
        return repository.findById(userId).map(settings -> switch (type) {
            case "reply" -> settings.isReplyEnabled();
            case "mention" -> settings.isMentionEnabled();
            case "like" -> settings.isLikeEnabled();
            case "follow" -> settings.isFollowEnabled();
            default -> true;
        }).orElse(true);
    }

    private UserSettingsDto toDto(UserNotificationSettings entity) {
        UserSettingsDto dto = new UserSettingsDto();
        dto.getInApp().setReply(entity.isReplyEnabled());
        dto.getInApp().setMention(entity.isMentionEnabled());
        dto.getInApp().setLike(entity.isLikeEnabled());
        dto.getInApp().setFollow(entity.isFollowEnabled());
        return dto;
    }
}
