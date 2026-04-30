package com.example.demo.storage.service;

import org.springframework.web.multipart.MultipartFile;

public interface ImageStorageProvider {
    boolean isAvailable();

    int order();

    String uploadImage(MultipartFile file);
}
