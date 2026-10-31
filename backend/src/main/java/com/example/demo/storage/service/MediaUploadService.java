package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaAssetStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Coordinates independent SQL transactions around an external object write. */
@Service
public class MediaUploadService {
    private final StorageService storage;
    private final MediaUploadRegistrationService registry;
    @Value("${app.media.registration-enabled:false}")
    private boolean registrationEnabled;

    public MediaUploadService(StorageService storage, MediaUploadRegistrationService registry) {
        this.storage = storage;
        this.registry = registry;
    }

    public Map<String, String> upload(Long ownerId, MultipartFile file) {
        if (!registrationEnabled) return Map.of("url", storage.uploadImage(file));
        StorageService.PreparedUpload upload = storage.prepareImage(file);
        String assetId = registry.begin(ownerId, upload.image().intent());
        // An exception can arrive after the provider persisted the bytes. Keep
        // the durable UPLOADING intent so reconciliation can verify the object.
        storage.writeImage(upload);
        // If this transaction fails, retain the UPLOADING intent for reconciliation.
        // Never delete the external object when commit/response outcome is uncertain.
        MediaAssetStatus result = registry.ready(assetId);
        Map<String, String> response = new LinkedHashMap<>();
        response.put("url", result.url());
        response.put("assetId", result.assetId());
        response.put("status", result.status());
        response.put("processingStatus", result.processingStatus());
        if (result.jobId() != null) response.put("jobId", result.jobId());
        return response;
    }
}
