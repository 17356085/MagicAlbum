package com.example.demo.storage.service;

import org.springframework.web.multipart.MultipartFile;

public interface ImageStorageProvider {
    boolean isAvailable();

    int order();

    PreparedImage prepareImage(MultipartFile file);

    void writeImage(PreparedImage image);

    default String uploadImage(MultipartFile file) {
        PreparedImage image = prepareImage(file);
        writeImage(image);
        return image.url();
    }
}
