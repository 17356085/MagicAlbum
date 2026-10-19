package com.example.demo.storage.service.provider.local;

import com.example.demo.storage.service.ImageStorageProvider;
import com.example.demo.storage.service.ImageUploadValidator;
import com.example.demo.storage.service.PreparedImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.time.LocalDate;
import java.util.Locale;
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
    public PreparedImage prepareImage(MultipartFile file) {
        if (pathPrefix == null || !pathPrefix.matches("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("Local pathPrefix must contain only safe path segments");
        }
        ImageUploadValidator.ValidatedImage validated = imageUploadValidator.validate(file);
        LocalDate today = LocalDate.now();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String relative = String.format(Locale.ROOT, "%s/%04d/%02d/%02d/%s%s", pathPrefix, today.getYear(), today.getMonthValue(), today.getDayOfMonth(), uuid, validated.extension());

        return PreparedImage.of("local", relative, "/uploads/" + relative, validated);
    }

    @Override
    public void writeImage(PreparedImage image) {
        if (pathPrefix == null || !"local".equals(image.provider()) || !image.objectKey().matches(
                java.util.regex.Pattern.quote(pathPrefix)
                + "/[0-9]{4}/[0-9]{2}/[0-9]{2}/[a-f0-9]{32}\\.(png|jpg|jpeg|gif|webp)")) {
            throw new IllegalArgumentException("Invalid local image plan");
        }
        try {
            Path configuredRoot = Paths.get(baseDir).toAbsolutePath().normalize();
            Files.createDirectories(configuredRoot);
            Path root = configuredRoot.toRealPath();
            Path target = root.resolve(image.objectKey()).normalize();
            if (!target.startsWith(root)) throw new IOException("Invalid object location");
            Path directory = root;
            for (Path segment : root.relativize(target.getParent())) {
                directory = directory.resolve(segment);
                try {
                    Files.createDirectory(directory);
                } catch (FileAlreadyExistsException existing) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw existing;
                }
            }
            Files.write(target, image.bytes(), StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传失败", e);
        }
    }
}
