package com.example.demo.storage.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import com.example.demo.storage.service.MediaUploadService;
import com.example.demo.storage.service.MediaUploadRegistrationService;
import com.example.demo.storage.dto.MediaAssetStatus;
import com.example.demo.storage.dto.MediaAssetStatusHttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {
    private final MediaUploadService mediaUploadService;
    private final MediaUploadRegistrationService registry;
    private final DistributedRateLimiter rateLimiter;
    private final boolean processingEnabled;

    public UploadController(MediaUploadService mediaUploadService, DistributedRateLimiter rateLimiter,
                            MediaUploadRegistrationService registry,
                            @Value("${app.media.processing-enabled:false}") boolean processingEnabled) {
        this.mediaUploadService = mediaUploadService;
        this.rateLimiter = rateLimiter;
        this.registry = registry;
        this.processingEnabled = processingEnabled;
    }

    @PostMapping("/images")
    public ResponseEntity<Map<String, ?>> uploadImage(
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) {
        Long userId = getUserId();
        rateLimiter.enforce("upload-user-minute", String.valueOf(userId), 60, 15);
        rateLimiter.enforce("upload-ip-minute", RequestIdentity.clientIp(request), 60, 60);
        Map<String, String> upload = mediaUploadService.upload(userId, file);
        if (upload == null || !upload.containsKey("assetId")) {
            return ResponseEntity.ok(upload);
        }

        Map<String, Object> response = new LinkedHashMap<>(upload);
        response.put("processingEnabled", processingEnabled);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/assets/{assetId}")
    public MediaAssetStatusHttpResponse assetStatus(@PathVariable String assetId) {
        MediaAssetStatus status = registry.findOwned(getUserId(), assetId);
        return new MediaAssetStatusHttpResponse(status, processingEnabled);
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
