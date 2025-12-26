package com.example.demo.user.service;

import com.example.demo.user.dto.ProfileDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.repo.UserProfileRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserProfileService {
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;

    public UserProfileService(UserRepository userRepository, UserProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
    }

    public ProfileDto getProfile(Long userId) {
        UserProfile up = null;
        try {
            up = profileRepository.findById(userId).orElse(null);
        } catch (RuntimeException e) {
            // 数据表缺失或查询异常时，回退到基础用户信息，避免 500
        }
        if (up != null) {
            ProfileDto dto = new ProfileDto();
            dto.setNickname(nullToEmpty(up.getNickname()));
            dto.setAvatarUrl(nullToEmpty(up.getAvatarUrl()));
            dto.setBio(nullToEmpty(up.getBio()));
            dto.setHomepageUrl(nullToEmpty(up.getHomepageUrl()));
            dto.setLocation(nullToEmpty(up.getLocation()));
            dto.setLinks(up.getLinks() != null ? up.getLinks() : List.of());
            return dto;
        }
        // fallback from basic user info when no profile record exists
        User u = userRepository.findById(userId).orElse(null);
        ProfileDto p = new ProfileDto();
        p.setNickname(u != null ? nullToEmpty(u.getUsername()) : "");
        p.setAvatarUrl("");
        p.setBio("");
        p.setHomepageUrl("");
        p.setLocation("");
        p.setLinks(List.of());
        return p;
    }

    public ProfileDto updateProfile(Long userId, ProfileDto payload) {
        ProfileDto p = (payload == null) ? new ProfileDto() : payload;
        if (p.getLinks() == null) p.setLinks(new ArrayList<>());
        UserProfile up = profileRepository.findById(userId).orElseGet(UserProfile::new);
        up.setUserId(userId);
        up.setNickname(p.getNickname());
        up.setAvatarUrl(p.getAvatarUrl());
        up.setBio(p.getBio());
        up.setHomepageUrl(p.getHomepageUrl());
        up.setLocation(p.getLocation());
        up.setLinks(p.getLinks());
        profileRepository.save(up);
        return getProfile(userId);
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }
}