package com.example.demo.user.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "user_profiles")
public class UserProfile {
    // 使用用户ID作为主键，确保一对一
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "nickname", length = 255)
    private String nickname;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "bio", length = 1024)
    private String bio;

    @Column(name = "homepage_url", length = 512)
    private String homepageUrl;

    @Column(name = "location", length = 255)
    private String location;

    @ElementCollection
    @CollectionTable(name = "user_profile_links", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "link", length = 1024)
    private List<String> links = new ArrayList<>();

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getHomepageUrl() { return homepageUrl; }
    public void setHomepageUrl(String homepageUrl) { this.homepageUrl = homepageUrl; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public List<String> getLinks() { return links; }
    public void setLinks(List<String> links) { this.links = (links == null) ? new ArrayList<>() : links; }
}