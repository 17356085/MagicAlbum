package com.example.demo.storage;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {
    private final S3StorageService s3StorageService;
    private final LocalStorageService localStorageService;

    public UploadController(S3StorageService s3StorageService, LocalStorageService localStorageService) {
        this.s3StorageService = s3StorageService;
        this.localStorageService = localStorageService;
    }

    @PostMapping("/images")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestPart("file") MultipartFile file) {
        String url;
        if (s3StorageService.isConfigured()) {
            url = s3StorageService.uploadImage(file);
        } else {
            url = localStorageService.uploadImage(file);
        }
        return ResponseEntity.ok(Map.of("url", url));
    }
}