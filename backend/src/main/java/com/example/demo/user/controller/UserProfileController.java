package com.example.demo.user.controller;

import com.example.demo.user.dto.ProfileDto;
import com.example.demo.user.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserProfileController extends AbstractUserControllerSupport {
    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileDto> getMe() {
        Long userId = getUserId();
        return ResponseEntity.ok(userProfileService.getProfile(userId, userId));
    }

    @PatchMapping("/me")
    public ResponseEntity<ProfileDto> updateMe(@Valid @RequestBody ProfileDto payload) {
        return ResponseEntity.ok(userProfileService.updateProfile(getUserId(), payload));
    }

    @GetMapping("/{id}/profile")
    public ResponseEntity<ProfileDto> getProfileById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(userProfileService.getProfile(id, getOptionalUserId()));
    }
}
