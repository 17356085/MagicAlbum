package com.example.demo.storage.service.provider.local;

import com.example.demo.storage.service.ImageStorageProvider;
import com.example.demo.storage.service.ImageUploadValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class LocalStorageService implements ImageStorageProvider {
    @Value("${app.storage.local.baseDir:uploads}")
    private String baseDir;
    @Value("${app.storage.local.pathPrefix:threads}")
    private String pathPrefix;
    private final ImageUploadValidator imageUploadValidator;

    public LocalStorageService(ImageUploadValidator imageUploadValidator) {
        this.imageUploadValidator = imageUploadValidator;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String uploadImage(MultipartFile file) {
        ImageUploadValidator.ValidatedImage validated = imageUploadValidator.validate(file);
        LocalDate today = LocalDate.now();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String relative = String.format("%s/%04d/%02d/%02d/%s%s", pathPrefix, today.getYear(), today.getMonthValue(), today.getDayOfMonth(), uuid, validated.extension());

        Path dir = Paths.get(baseDir).toAbsolutePath();
        Path target = dir.resolve(relative.replace("\\", "/"));
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, validated.bytes());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传失败", e);
        }
        // 通过 WebMvc 的资源映射对外暴露：/uploads/** → baseDir
        String url = "/uploads/" + relative;
        return url.replace("\\", "/");
    }
}
