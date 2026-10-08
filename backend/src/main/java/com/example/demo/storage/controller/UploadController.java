package com.example.demo.storage.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import com.example.demo.storage.service.StorageService;
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
    private final StorageService storageService;
    private final DistributedRateLimiter rateLimiter;

    public UploadController(StorageService storageService, DistributedRateLimiter rateLimiter) {
        this.storageService = storageService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/images")
    public ResponseEntity<Map<String, String>> uploadImage(
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) {
        Long userId = getUserId();
        rateLimiter.enforce("upload-user-minute", String.valueOf(userId), 60, 15);
        rateLimiter.enforce("upload-ip-minute", RequestIdentity.clientIp(request), 60, 60);
        String url = storageService.uploadImage(file);
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
