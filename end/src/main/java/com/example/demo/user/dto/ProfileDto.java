package com.example.demo.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public class ProfileDto {
    private String username;

    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    @Size(max = 512, message = "头像地址长度不能超过 512")
    @Pattern(
            regexp = "^(|https?://.+|/uploads/.+)$",
            message = "头像地址仅支持 http/https 或站内 /uploads 路径"
    )
    private String avatarUrl;

    @Size(max = 1000, message = "个人简介长度不能超过 1000")
    private String bio;

    @Size(max = 512, message = "主页链接长度不能超过 512")
    @Pattern(
            regexp = "^(|https?://.+)$",
            message = "主页链接仅支持 http 或 https"
    )
    private String homepageUrl;

    @Size(max = 255, message = "所在地长度不能超过 255")
    private String location;
    private List<String> links;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

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
    public void setLinks(List<String> links) { this.links = links; }
}
