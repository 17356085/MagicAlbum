package com.example.demo.user.service;

import com.example.demo.user.dto.ProfileDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.repo.UserProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserProfileService {
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;

    public UserProfileService(UserRepository userRepository, UserProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
    }

    public ProfileDto getProfile(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        UserProfile up = profileRepository.findById(userId).orElse(null);
        if (up != null) {
            ProfileDto dto = new ProfileDto();
            dto.setNickname(nullToEmpty(up.getNickname()));
            dto.setAvatarUrl(nullToEmpty(up.getAvatarUrl()));
            dto.setBio(nullToEmpty(up.getBio()));
            dto.setHomepageUrl(nullToEmpty(up.getHomepageUrl()));
            dto.setLocation(nullToEmpty(up.getLocation()));
            dto.setLinks(sanitizeLinks(up.getLinks()));
            dto.setUsername(nullToEmpty(u.getUsername()));
            return dto;
        }
        ProfileDto p = new ProfileDto();
        p.setUsername(nullToEmpty(u.getUsername()));
        p.setNickname(nullToEmpty(u.getUsername()));
        p.setAvatarUrl("");
        p.setBio("");
        p.setHomepageUrl("");
        p.setLocation("");
        p.setLinks(List.of());
        return p;
    }

    public Map<Long, ProfileDto> getProfiles(Collection<Long> userIds) {
        Map<Long, ProfileDto> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) return result;

        List<User> users = userRepository.findAllById(userIds);
        Map<Long, User> userMap = new HashMap<>();
        for (User u : users) {
            userMap.put(u.getId(), u);
        }

        Map<Long, UserProfile> profileMap = new HashMap<>();
        for (UserProfile profile : profileRepository.findByUserIdIn(userIds)) {
            profileMap.put(profile.getUserId(), profile);
        }

        for (Long userId : userIds) {
            User user = userMap.get(userId);
            if (user == null) continue;
            UserProfile profile = profileMap.get(userId);
            ProfileDto dto = new ProfileDto();
            dto.setUsername(nullToEmpty(user.getUsername()));
            if (profile != null) {
                dto.setNickname(nullToEmpty(profile.getNickname()));
                dto.setAvatarUrl(nullToEmpty(profile.getAvatarUrl()));
                dto.setBio(nullToEmpty(profile.getBio()));
                dto.setHomepageUrl(nullToEmpty(profile.getHomepageUrl()));
                dto.setLocation(nullToEmpty(profile.getLocation()));
                dto.setLinks(sanitizeLinks(profile.getLinks()));
            } else {
                dto.setNickname(nullToEmpty(user.getUsername()));
                dto.setAvatarUrl("");
                dto.setBio("");
                dto.setHomepageUrl("");
                dto.setLocation("");
                dto.setLinks(List.of());
            }
            result.put(userId, dto);
        }
        return result;
    }

    public ProfileDto updateProfile(Long userId, ProfileDto payload) {
        ProfileDto p = (payload == null) ? new ProfileDto() : payload;
        userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        UserProfile up = profileRepository.findById(userId).orElseGet(UserProfile::new);
        up.setUserId(userId);
        up.setNickname(trimToNull(p.getNickname()));
        up.setAvatarUrl(trimToNull(p.getAvatarUrl()));
        up.setBio(trimToNull(p.getBio()));
        up.setHomepageUrl(trimToNull(p.getHomepageUrl()));
        up.setLocation(trimToNull(p.getLocation()));
        up.setLinks(sanitizeLinks(p.getLinks()));
        profileRepository.save(up);
        return getProfile(userId);
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }
    private String trimToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    private List<String> sanitizeLinks(List<String> links) {
        if (links == null || links.isEmpty()) return new ArrayList<>();
        List<String> cleaned = new ArrayList<>();
        for (String link : links) {
            String val = trimToNull(link);
            if (val == null) continue;
            if (!(val.startsWith("http://") || val.startsWith("https://"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "个人链接仅支持 http 或 https");
            }
            if (val.length() > 1024) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "个人链接长度不能超过 1024");
            }
            cleaned.add(val);
            if (cleaned.size() >= 10) break;
        }
        return cleaned;
    }
}
