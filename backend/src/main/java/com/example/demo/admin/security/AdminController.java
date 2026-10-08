package com.example.demo.admin.security;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminAccessService adminAccessService;

    public AdminController(AdminAccessService adminAccessService) {
        this.adminAccessService = adminAccessService;
    }

    @GetMapping("/me")
    public Map<String, Boolean> me(Authentication authentication) {
        adminAccessService.requireAdmin(currentUserId(authentication));
        return Map.of("admin", true);
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Number number ? number.longValue() : null;
    }
}
