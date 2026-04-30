package com.example.demo.user.controller;

import com.example.demo.user.dto.UserSettingsDto;
import com.example.demo.user.service.UserSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserSettingsController extends AbstractUserControllerSupport {
    private final UserSettingsService userSettingsService;

    public UserSettingsController(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    @GetMapping("/me/settings")
    public ResponseEntity<UserSettingsDto> getMySettings() {
        return ResponseEntity.ok(userSettingsService.getSettings(getUserId()));
    }

    @PatchMapping("/me/settings")
    public ResponseEntity<UserSettingsDto> updateMySettings(@RequestBody UserSettingsDto payload) {
        return ResponseEntity.ok(userSettingsService.updateSettings(getUserId(), payload));
    }
}
