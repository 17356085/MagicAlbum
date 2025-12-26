package com.example.demo.user.dto;

import java.util.List;

public class ProfileDto {
    private String nickname;
    private String avatarUrl;
    private String bio;
    private String homepageUrl;
    private String location;
    private List<String> links;

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