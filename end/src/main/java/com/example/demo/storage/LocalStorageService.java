package com.example.demo.storage;

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
public class LocalStorageService {
    @Value("${app.storage.local.baseDir:uploads}")
    private String baseDir;
    @Value("${app.storage.local.pathPrefix:threads}")
    private String pathPrefix;

    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持图片上传");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片大小不能超过10MB");
        }

        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.'));
        }
        LocalDate today = LocalDate.now();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String relative = String.format("%s/%04d/%02d/%02d/%s%s", pathPrefix, today.getYear(), today.getMonthValue(), today.getDayOfMonth(), uuid, ext);

        Path dir = Paths.get(baseDir).toAbsolutePath();
        Path target = dir.resolve(relative.replace("\\", "/"));
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传失败", e);
        }
        // 通过 WebMvc 的资源映射对外暴露：/uploads/** → baseDir
        String url = "/uploads/" + relative;
        return url.replace("\\", "/");
    }
}