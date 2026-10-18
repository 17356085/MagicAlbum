package com.example.demo.storage.service.provider.s3;

import com.example.demo.storage.service.ImageStorageProvider;
import com.example.demo.storage.service.ImageUploadValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class S3StorageService implements ImageStorageProvider {
    @Value("${app.storage.s3.bucket:}")
    private String bucket;
    @Value("${app.storage.s3.region:}")
    private String region;
    @Value("${app.storage.s3.endpoint:}")
    private String endpoint;
    @Value("${app.storage.s3.accessKey:}")
    private String accessKey;
    @Value("${app.storage.s3.secretKey:}")
    private String secretKey;
    @Value("${app.storage.s3.publicBaseUrl:}")
    private String publicBaseUrl;
    @Value("${app.storage.s3.aclPublicRead:false}")
    private boolean aclPublicRead;
    @Value("${app.storage.s3.pathPrefix:threads}")
    private String pathPrefix;
    private final ImageUploadValidator imageUploadValidator;
    private volatile S3Client client;

    public S3StorageService(ImageUploadValidator imageUploadValidator) {
        this.imageUploadValidator = imageUploadValidator;
    }

    public boolean isConfigured() {
        return bucket != null && !bucket.isBlank()
                && region != null && !region.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    @Override
    public boolean isAvailable() {
        return isConfigured();
    }

    @Override
    public int order() {
        return 10;
    }

    private S3Client client() {
        S3Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = buildClient();
            }
            return client;
        }
    }

    private S3Client buildClient() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 存储未配置完整");
        }
        S3ClientBuilder b = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isBlank()) {
            b = b.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return b.build();
    }

    @Override
    public String uploadImage(MultipartFile file) {
        validatePrefix();
        ImageUploadValidator.ValidatedImage validated = imageUploadValidator.validate(file);
        LocalDate today = LocalDate.now();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String key = String.format("%s/%04d/%02d/%02d/%s%s", pathPrefix, today.getYear(), today.getMonthValue(), today.getDayOfMonth(), uuid, validated.extension());

        PutObjectRequest.Builder putReq = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(validated.contentType());
        if (aclPublicRead) {
            putReq = putReq.acl(ObjectCannedACL.PUBLIC_READ);
        }
        try {
            client().putObject(putReq.build(), RequestBody.fromBytes(validated.bytes()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传失败", e);
        }

        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            // Persist a stable application URL, never an expiring signed URL in Markdown.
            return "/uploads/s3/" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String base = publicBaseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + key;
    }

    public URI resolveReadUrl(String token) {
        if (!isConfigured()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        validatePrefix();
        String key;
        try {
            if (token == null || token.length() > 1024) throw new IllegalArgumentException();
            key = new String(java.util.Base64.getUrlDecoder().decode(token), java.nio.charset.StandardCharsets.UTF_8);
            if (!key.matches(java.util.regex.Pattern.quote(pathPrefix)
                    + "/[0-9]{4}/[0-9]{2}/[0-9]{2}/[a-f0-9]{32}\\.(png|jpg|jpeg|gif|webp)")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        var builder = software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).serviceConfiguration(
                    software.amazon.awssdk.services.s3.S3Configuration.builder()
                            .pathStyleAccessEnabled(true).checksumValidationEnabled(false).build());
        }
        try (var presigner = builder.build()) {
            return presigner.presignGetObject(b -> b.signatureDuration(java.time.Duration.ofMinutes(5))
                    .getObjectRequest(r -> r.bucket(bucket).key(key))).url().toURI();
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalStateException("Invalid storage read URL", invalid);
        }
    }

    private void validatePrefix() {
        if (pathPrefix == null || !pathPrefix.matches("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("S3 pathPrefix must contain only safe path segments");
        }
    }

    @PreDestroy
    void closeClient() {
        S3Client current = client;
        if (current != null) {
            current.close();
            client = null;
        }
    }
}
