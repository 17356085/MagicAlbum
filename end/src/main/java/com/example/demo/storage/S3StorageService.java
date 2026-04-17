package com.example.demo.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
public class S3StorageService {
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
    @Value("${app.storage.s3.aclPublicRead:true}")
    private boolean aclPublicRead;
    @Value("${app.storage.s3.pathPrefix:threads}")
    private String pathPrefix;
    private final ImageUploadValidator imageUploadValidator;

    public S3StorageService(ImageUploadValidator imageUploadValidator) {
        this.imageUploadValidator = imageUploadValidator;
    }

    public boolean isConfigured() {
        return bucket != null && !bucket.isBlank()
                && region != null && !region.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    private S3Client buildClient() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 存储未配置完整");
        }
        S3ClientBuilder b = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isBlank()) {
            b = b.endpointOverride(URI.create(endpoint));
        }
        return b.build();
    }

    public String uploadImage(MultipartFile file) {
        ImageUploadValidator.ValidatedImage validated = imageUploadValidator.validate(file);
        LocalDate today = LocalDate.now();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String key = String.format("%s/%04d/%02d/%02d/%s%s", pathPrefix, today.getYear(), today.getMonthValue(), today.getDayOfMonth(), uuid, validated.extension());

        S3Client client = buildClient();
        PutObjectRequest.Builder putReq = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(validated.contentType());
        if (aclPublicRead) {
            putReq = putReq.acl(ObjectCannedACL.PUBLIC_READ);
        }
        try {
            client.putObject(putReq.build(), RequestBody.fromBytes(validated.bytes()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传失败", e);
        }

        String base = (publicBaseUrl != null && !publicBaseUrl.isBlank()) ? publicBaseUrl : (endpoint != null && !endpoint.isBlank() ? endpoint : ("https://" + bucket + ".s3." + region + ".amazonaws.com"));
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + key;
    }
}
