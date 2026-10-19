package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaAssetStatus;
import com.example.demo.storage.dto.MediaUploadIntent;
import com.example.demo.storage.entity.MediaAsset;
import com.example.demo.storage.entity.MediaProcessingJob;
import com.example.demo.storage.entity.MediaRequestOutbox;
import com.example.demo.storage.repo.MediaAssetRepository;
import com.example.demo.storage.repo.MediaProcessingJobRepository;
import com.example.demo.storage.repo.MediaRequestOutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Owns the durable state around an external media object write.
 *
 * <p>The begin, ready and failed operations intentionally have independent
 * transactions. The object write happens between begin and ready, so an
 * uncertain external outcome remains represented as UPLOADING.</p>
 */
@Service
public class MediaUploadRegistrationService {

    public static final String PRESET_PREVIEW = "preview";
    public static final String PROCESSOR_VERSION = "1";
    public static final int JOB_VERSION = 1;

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_PROVIDER_LENGTH = 16;
    private static final int MAX_OBJECT_KEY_LENGTH = 512;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_CONTENT_TYPE_LENGTH = 32;
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("local", "s3");
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Pattern LOWER_HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final MediaAssetRepository assetRepository;
    private final MediaProcessingJobRepository jobRepository;
    private final MediaRequestOutboxRepository outboxRepository;

    public MediaUploadRegistrationService(
            MediaAssetRepository assetRepository,
            MediaProcessingJobRepository jobRepository,
            MediaRequestOutboxRepository outboxRepository
    ) {
        this.assetRepository = assetRepository;
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Registers provider metadata before the external object write begins.
     */
    @Transactional
    public String begin(Long ownerId, MediaUploadIntent intent) {
        validateBeginInput(ownerId, intent);

        MediaAsset asset = new MediaAsset();
        asset.setAssetId(UUID.randomUUID().toString());
        asset.setOwnerId(ownerId);
        asset.setProvider(intent.provider());
        asset.setObjectKey(intent.objectKey());
        asset.setUrl(intent.url());
        asset.setContentType(intent.contentType());
        asset.setByteSize(intent.byteSize());
        asset.setSha256(intent.sha256());
        asset.setStatus(MediaAsset.STATUS_UPLOADING);
        asset.setProcessingStatus(MediaAsset.PROCESSING_NOT_REQUESTED);
        assetRepository.saveAndFlush(asset);
        return asset.getAssetId();
    }

    /**
     * Commits the successful external write and, for JPEG/PNG, its first
     * preview job and durable request outbox row in one transaction.
     */
    @Transactional
    public MediaAssetStatus ready(String assetId) {
        MediaAsset asset = assetRepository.findByAssetIdForUpdate(assetId)
                .orElseThrow(this::assetNotFound);

        if (!MediaAsset.STATUS_UPLOADING.equals(asset.getStatus())) {
            return toStatus(asset);
        }

        asset.setStatus(MediaAsset.STATUS_READY);
        if (isProcessable(asset.getContentType())) {
            asset.setProcessingStatus(MediaAsset.PROCESSING_PENDING);
            String jobId = UUID.randomUUID().toString();

            MediaProcessingJob job = new MediaProcessingJob();
            job.setJobId(jobId);
            job.setAssetId(asset.getAssetId());
            job.setJobVersion(JOB_VERSION);
            job.setPreset(PRESET_PREVIEW);
            job.setProcessorVersion(PROCESSOR_VERSION);
            job.setStatus(MediaProcessingJob.STATUS_PENDING);
            job.setAttempts(0);
            assetRepository.save(asset);
            jobRepository.save(job);

            MediaRequestOutbox outbox = new MediaRequestOutbox();
            outbox.setEventId(UUID.randomUUID().toString());
            outbox.setAssetId(asset.getAssetId());
            outbox.setJobId(jobId);
            outbox.setJobVersion(JOB_VERSION);
            outbox.setPreset(PRESET_PREVIEW);
            outbox.setProcessorVersion(PROCESSOR_VERSION);
            outbox.setStatus(MediaRequestOutbox.STATUS_PENDING);
            // Flush all three writes before returning so a durable outbox
            // constraint failure rolls the status change back as one unit.
            outboxRepository.saveAndFlush(outbox);
            return new MediaAssetStatus(
                    asset.getAssetId(), asset.getUrl(), asset.getStatus(), jobId, asset.getProcessingStatus());
        }

        asset.setProcessingStatus(MediaAsset.PROCESSING_UNSUPPORTED);
        assetRepository.saveAndFlush(asset);
        return toStatus(asset);
    }

    /**
     * Records a failed external write only while the object is still pending.
     */
    @Transactional
    public MediaAssetStatus failed(String assetId) {
        MediaAsset asset = assetRepository.findByAssetIdForUpdate(assetId)
                .orElseThrow(this::assetNotFound);

        if (MediaAsset.STATUS_UPLOADING.equals(asset.getStatus())) {
            asset.setStatus(MediaAsset.STATUS_UPLOAD_FAILED);
            asset.setProcessingStatus(MediaAsset.PROCESSING_NOT_REQUESTED);
            assetRepository.saveAndFlush(asset);
        }
        return toStatus(asset);
    }

    /**
     * Reads public state only when the owner and asset ID match. A different
     * owner receives the same not-found result as an unknown asset.
     */
    @Transactional(readOnly = true)
    public MediaAssetStatus findOwned(Long ownerId, String assetId) {
        requireOwnerId(ownerId);
        return assetRepository.findByAssetIdAndOwnerId(assetId, ownerId)
                .map(this::toStatus)
                .orElseThrow(this::assetNotFound);
    }

    private MediaAssetStatus toStatus(MediaAsset asset) {
        String jobId = null;
        String processingStatus = asset.getProcessingStatus();
        String previewUrl = null;
        String errorCode = null;
        MediaProcessingJob job = jobRepository.findFirstByAssetIdOrderByJobVersionDesc(
                asset.getAssetId()).orElse(null);
        if (job != null) {
            jobId = job.getJobId();
            processingStatus = switch (job.getStatus()) {
                case "PENDING" -> MediaAsset.PROCESSING_PENDING;
                case "PROCESSING" -> "PROCESSING";
                case "SUCCEEDED" -> "SUCCEEDED";
                case "FAILED" -> "FAILED";
                case "CANCELLED" -> "CANCELLED";
                default -> processingStatus;
            };
            if ("SUCCEEDED".equals(job.getStatus())) {
                previewUrl = job.getOutputUrl();
            }
            if ("FAILED".equals(job.getStatus())) {
                errorCode = job.getErrorCode();
            }
        }
        return new MediaAssetStatus(
                asset.getAssetId(), asset.getUrl(), asset.getStatus(), jobId, processingStatus, previewUrl, errorCode);
    }

    private boolean isProcessable(String contentType) {
        return "image/jpeg".equals(contentType) || "image/png".equals(contentType);
    }

    private void validateBeginInput(Long ownerId, MediaUploadIntent intent) {
        requireOwnerId(ownerId);
        if (intent == null) {
            throw new IllegalArgumentException("media upload intent is required");
        }
        requireText(intent.provider(), "provider", MAX_PROVIDER_LENGTH);
        if (!SUPPORTED_PROVIDERS.contains(intent.provider())) {
            throw new IllegalArgumentException("unsupported media provider");
        }
        requireText(intent.objectKey(), "objectKey", MAX_OBJECT_KEY_LENGTH);
        requireText(intent.url(), "url", MAX_URL_LENGTH);
        requireText(intent.contentType(), "contentType", MAX_CONTENT_TYPE_LENGTH);
        if (!SUPPORTED_CONTENT_TYPES.contains(intent.contentType())) {
            throw new IllegalArgumentException("unsupported image content type");
        }
        if (intent.byteSize() < 1 || intent.byteSize() > MAX_BYTES) {
            throw new IllegalArgumentException("image size must be between 1 byte and 10 MiB");
        }
        if (intent.sha256() == null || !LOWER_HEX_SHA256.matcher(intent.sha256()).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal SHA-256");
        }
    }

    private void requireOwnerId(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be positive");
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is blank or exceeds its maximum length");
        }
    }

    private ResponseStatusException assetNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体资源不存在");
    }
}
