package com.example.demo.storage.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

@Service
public class StorageService {
    private final List<ImageStorageProvider> providers;

    public StorageService(List<ImageStorageProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(ImageStorageProvider::order))
                .toList();
    }

    public String uploadImage(MultipartFile file) {
        return resolveProvider().uploadImage(file);
    }

    private ImageStorageProvider resolveProvider() {
        return providers.stream()
                .filter(ImageStorageProvider::isAvailable)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未找到可用的图片存储实现"));
    }
}
