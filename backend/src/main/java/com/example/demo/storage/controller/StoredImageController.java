package com.example.demo.storage.controller;

import com.example.demo.storage.service.provider.s3.S3StorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Forum images are public resources, even when the underlying bucket is private. */
@RestController
public class StoredImageController {
    private final S3StorageService storage;

    public StoredImageController(S3StorageService storage) { this.storage = storage; }

    @GetMapping("/uploads/s3/{token}")
    public ResponseEntity<Void> read(@PathVariable String token) {
        return ResponseEntity.status(302).cacheControl(CacheControl.noStore())
                .location(storage.resolveReadUrl(token)).build();
    }
}
