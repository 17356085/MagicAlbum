package com.example.demo.storage;

import com.example.demo.common.SimpleRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {
    private final S3StorageService s3StorageService;
    private final LocalStorageService localStorageService;
    private final SimpleRateLimiter rateLimiter;

    public UploadController(S3StorageService s3StorageService, LocalStorageService localStorageService, SimpleRateLimiter rateLimiter) {
        this.s3StorageService = s3StorageService;
        this.localStorageService = localStorageService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/images")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestPart("file") MultipartFile file) {
        Long userId = getUserId();
        boolean allowed = rateLimiter.allow(userId, 60, 15);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "上传过于频繁，请稍后再试");
        }
        String url;
        if (s3StorageService.isConfigured()) {
            url = s3StorageService.uploadImage(file);
        } else {
            url = localStorageService.uploadImage(file);
        }
        return ResponseEntity.ok(Map.of("url", url));
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
