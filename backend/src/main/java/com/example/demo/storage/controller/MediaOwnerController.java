package com.example.demo.storage.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.storage.dto.MediaAssetStatus;
import com.example.demo.storage.service.MediaJobService;
import com.example.demo.storage.service.MediaUploadRegistrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/uploads/assets")
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaOwnerController {
    private final MediaJobService jobs;
    private final MediaUploadRegistrationService registry;
    private final DistributedRateLimiter limiter;

    public MediaOwnerController(MediaJobService jobs, MediaUploadRegistrationService registry,
                                DistributedRateLimiter limiter) {
        this.jobs = jobs;
        this.registry = registry;
        this.limiter = limiter;
    }

    @PostMapping("/{assetId}/cancel")
    public ResponseEntity<MediaAssetStatus> cancel(@PathVariable String assetId, Authentication authentication) {
        Long owner = owner(authentication);
        jobs.cancelOwned(assetId, owner);
        return state(owner, assetId);
    }

    @PostMapping("/{assetId}/retry")
    public ResponseEntity<MediaAssetStatus> retry(@PathVariable String assetId, Authentication authentication) {
        Long owner = owner(authentication);
        limiter.enforce("media-retry-user-minute", owner.toString(), 60, 15);
        jobs.retryOwned(assetId, owner);
        return state(owner, assetId);
    }

    private ResponseEntity<MediaAssetStatus> state(Long owner, String assetId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(registry.findOwned(owner, assetId));
    }

    private Long owner(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Long id) return id;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
