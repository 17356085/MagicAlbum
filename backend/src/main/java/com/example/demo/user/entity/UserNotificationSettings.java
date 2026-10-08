package com.example.demo.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_notification_settings")
public class UserNotificationSettings {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "reply_enabled", nullable = false)
    private boolean replyEnabled = true;

    @Column(name = "mention_enabled", nullable = false)
    private boolean mentionEnabled = true;

    @Column(name = "like_enabled", nullable = false)
    private boolean likeEnabled = true;

    @Column(name = "follow_enabled", nullable = false)
    private boolean followEnabled = true;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public boolean isReplyEnabled() { return replyEnabled; }
    public void setReplyEnabled(boolean replyEnabled) { this.replyEnabled = replyEnabled; }
    public boolean isMentionEnabled() { return mentionEnabled; }
    public void setMentionEnabled(boolean mentionEnabled) { this.mentionEnabled = mentionEnabled; }
    public boolean isLikeEnabled() { return likeEnabled; }
    public void setLikeEnabled(boolean likeEnabled) { this.likeEnabled = likeEnabled; }
    public boolean isFollowEnabled() { return followEnabled; }
    public void setFollowEnabled(boolean followEnabled) { this.followEnabled = followEnabled; }
}
