package com.example.demo.user.controller;

import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.dto.ProfileDto;
import com.example.demo.user.dto.UserSettingsDto;
import com.example.demo.user.dto.BasicInfoUpdateRequest;
import com.example.demo.user.dto.ChangePasswordRequest;
import com.example.demo.user.service.UserService;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.posts.service.PostService;
import com.example.demo.user.service.UserProfileService;
import com.example.demo.user.service.UserSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Page;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final UserProfileService userProfileService;
    private final UserSettingsService userSettingsService;
    private final ThreadService threadService;
    private final PostService postService;

    public UserController(UserService userService, UserProfileService userProfileService, UserSettingsService userSettingsService, ThreadService threadService, PostService postService) {
        this.userService = userService;
        this.userProfileService = userProfileService;
        this.userSettingsService = userSettingsService;
        this.threadService = threadService;
        this.postService = postService;
    }

    @GetMapping("/availability")
    public Map<String, Boolean> checkAvailability(@RequestParam String username) {
        boolean available = userService.isUsernameAvailable(username);
        return Map.of("available", available);
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest req) {
        UserDto created = userService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<java.util.Map<String, Object>> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "fields", required = false) String fields
    ) {
        Page<UserDto> p = userService.list(q, page, size, fields);

        java.util.List<Long> userIds = p.getContent().stream()
                .map(UserDto::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        java.util.Map<Long, ProfileDto> profileMap = userProfileService.getProfiles(userIds);

        java.util.List<UserSummaryDto> summaries = p.getContent().stream().map(u -> {
            UserSummaryDto dto = new UserSummaryDto();
            dto.setId(u.getId());
            dto.setUsername(u.getUsername());
            dto.setCreatedAt(u.getCreatedAt());

            ProfileDto profile = profileMap.get(u.getId());
            dto.setNickname(profile != null ? profile.getNickname() : "");
            dto.setAvatarUrl(profile != null ? profile.getAvatarUrl() : "");
            return dto;
        }).toList();

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", summaries);
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileDto> getMe() {
        Long userId = getUserId();
        ProfileDto profile = userProfileService.getProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/me/basic")
    public ResponseEntity<UserDto> getMyBasic() {
        Long userId = getUserId();
        UserDto dto = userService.getById(userId);
        return ResponseEntity.ok(dto);
    }

    @PatchMapping("/me")
    public ResponseEntity<ProfileDto> updateMe(@Valid @RequestBody ProfileDto payload) {
        Long userId = getUserId();
        ProfileDto updated = userProfileService.updateProfile(userId, payload);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/me/settings")
    public ResponseEntity<UserSettingsDto> getMySettings() {
        Long userId = getUserId();
        UserSettingsDto settings = userSettingsService.getSettings(userId);
        return ResponseEntity.ok(settings);
    }

    @PatchMapping("/me/settings")
    public ResponseEntity<UserSettingsDto> updateMySettings(@RequestBody UserSettingsDto payload) {
        Long userId = getUserId();
        UserSettingsDto updated = userSettingsService.updateSettings(userId, payload);
        return ResponseEntity.ok(updated);
    }

    @PatchMapping("/me/basic")
    public ResponseEntity<UserDto> updateMyBasic(@Valid @RequestBody BasicInfoUpdateRequest payload) {
        Long userId = getUserId();
        UserDto updated = userService.updateBasicInfo(userId, payload);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changeMyPassword(@Valid @RequestBody ChangePasswordRequest payload) {
        Long userId = getUserId();
        userService.changePassword(userId, payload);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/me/threads")
    public ResponseEntity<java.util.Map<String, Object>> listMyThreads(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "sort", defaultValue = "updatedAt") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Long userId = getUserId();
        org.springframework.data.domain.Page<com.example.demo.threads.dto.ThreadDto> p = threadService.listByAuthor(userId, q, sectionId, sort, page, size);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", p.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }

    // 公共接口：按指定用户ID分页列出其主题帖（每页默认10条）
    @GetMapping("/{id}/threads")
    public ResponseEntity<java.util.Map<String, Object>> listThreadsByUser(
            @PathVariable("id") Long id,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "sort", defaultValue = "updatedAt") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        org.springframework.data.domain.Page<com.example.demo.threads.dto.ThreadDto> p = threadService.listByAuthor(id, q, sectionId, sort, page, size);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", p.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me/posts")
    public ResponseEntity<java.util.Map<String, Object>> listMyPosts(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sectionId", required = false) Long sectionId,
            @RequestParam(value = "sort", defaultValue = "createdAt") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Long userId = getUserId();
        org.springframework.data.domain.Page<com.example.demo.posts.dto.PostDto> p = postService.listByAuthor(userId, q, sectionId, sort, page, size);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", p.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", p.getTotalElements());
        return ResponseEntity.ok(body);
    }

    // 公共用户资料：无需登录，展示指定用户的 Profile（昵称、头像、个人信息）
    @GetMapping("/{id}/profile")
    public ResponseEntity<ProfileDto> getProfileById(
            @PathVariable("id") Long id
    ) {
        ProfileDto profile = userProfileService.getProfile(id);
        return ResponseEntity.ok(profile);
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
