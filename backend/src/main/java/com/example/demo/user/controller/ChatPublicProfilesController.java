package com.example.demo.user.controller;

import com.example.demo.user.service.UserProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class ChatPublicProfilesController {
    private final UserProfileService profiles;

    public ChatPublicProfilesController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping("/internal/v1/users/public-profiles:batchGet")
    public ResponseEntity<ProfileBatch> find(@Valid @RequestBody ProfileRequest request) {
        List<Long> ids = request.userIds().stream().distinct().toList();
        var byId = profiles.getUserSummaries(ids);
        List<PublicProfile> visibleProfiles = new ArrayList<>();
        List<String> missingIds = new ArrayList<>();
        for (Long id : ids) {
            var profile = byId.get(id);
            if (profile == null) {
                missingIds.add(id.toString());
                continue;
            }
            visibleProfiles.add(new PublicProfile(id.toString(), profile.getUsername(),
                    profile.getNickname(), profile.getAvatarUrl()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ProfileBatch(visibleProfiles, missingIds));
    }

    public record ProfileRequest(@NotNull @Size(max = 100) List<@NotNull @Positive Long> userIds) {}

    public record ProfileBatch(List<PublicProfile> profiles, List<String> missingIds) {}

    public record PublicProfile(String id, String username, String nickname, String avatarUrl) {}
}
