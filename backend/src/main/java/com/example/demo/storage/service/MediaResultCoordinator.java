package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaExecutionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;

/**
 * Validates worker output and coordinates the reserve/write/accept sequence.
 * The object store is only touched outside the short JDBC transactions in
 * {@link MediaJobService}.
 */
@Service
public class MediaResultCoordinator {

    private static final int MAX_OUTPUT_EDGE = 1600;
    private static final HexFormat HEX = HexFormat.of();

    private final MediaJobService jobService;
    private final ImageUploadValidator imageUploadValidator;
    private final MediaObjectStore objectStore;

    public MediaResultCoordinator(
            MediaJobService jobService,
            ImageUploadValidator imageUploadValidator,
            MediaObjectStore objectStore
    ) {
        this.jobService = Objects.requireNonNull(jobService, "jobService");
        this.imageUploadValidator = Objects.requireNonNull(imageUploadValidator, "imageUploadValidator");
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
    }

    /**
     * Validates output bytes, reserves a deterministic object key, writes the
     * object, and finally accepts the result under a fresh job transaction.
     */
    public MediaExecutionResponse submit(
            String jobId,
            byte[] outputBytes,
            int jobVersion,
            String inputSha256,
            String outputSha256,
            String processorVersion,
            int width,
            int height,
            String leaseToken
    ) {
        if (outputBytes == null || outputBytes.length == 0 || outputBytes.length > MediaObjectStore.MAX_BYTES
                || jobVersion < 1
                || blank(inputSha256) || blank(outputSha256) || blank(processorVersion)
                || blank(leaseToken) || width <= 0 || height <= 0
                || width > MAX_OUTPUT_EDGE || height > MAX_OUTPUT_EDGE) {
            throw badRequest("媒体结果元数据不合法");
        }

        MediaJobService.JobLeaseSnapshot snapshot = jobService.lockForResult(jobId, jobVersion, leaseToken);
        if (!inputSha256.equals(snapshot.inputSha256())) {
            throw conflict("媒体输入摘要不匹配");
        }
        if (!processorVersion.equals(snapshot.processorVersion())) {
            throw conflict("媒体处理版本冲突");
        }

        DetectedImage image = validateImage(outputBytes);
        if (image.width() != width || image.height() != height) {
            throw badRequest("媒体结果尺寸不匹配");
        }
        String actualSha256 = sha256(outputBytes);
        if (!actualSha256.equals(outputSha256)) {
            throw badRequest("媒体结果摘要不匹配");
        }
        if (!outputSha256.matches("[0-9a-f]{64}")) {
            throw badRequest("媒体结果摘要不合法");
        }

        MediaObjectStore.StoredObject stored;
        try {
            stored = objectStore.planDerived(
                    snapshot.inputProvider(), snapshot.assetId(), jobVersion, snapshot.preset(),
                    processorVersion, outputSha256, image.contentType());
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (stored == null || blank(stored.provider()) || blank(stored.objectKey()) || blank(stored.url())
                || !snapshot.inputProvider().equals(stored.provider())) {
            throw unavailable();
        }

        MediaJobService.CandidateMetadata candidate = new MediaJobService.CandidateMetadata(
                stored.objectKey(), stored.url(), outputSha256, image.contentType(), outputBytes.length, width, height);
        MediaJobService.Reservation reservation = jobService.reserveResult(
                jobId, jobVersion, leaseToken, candidate);
        if (reservation.alreadyAccepted()) {
            return new MediaExecutionResponse(MediaJobService.STATUS_SUCCEEDED);
        }

        try {
            objectStore.write(stored, outputBytes);
        } catch (RuntimeException e) {
            // The candidate remains durable so a retry can write/accept it.
            throw unavailable();
        }
        return jobService.acceptResult(jobId, jobVersion, leaseToken, candidate);
    }

    private DetectedImage validateImage(byte[] bytes) {
        DetectedImage detected = detectHeader(bytes);
        if (detected == null || (!"image/jpeg".equals(detected.contentType())
                && !"image/png".equals(detected.contentType()))) {
            throw badRequest("媒体结果格式不受支持");
        }
        String filename = "image/jpeg".equals(detected.contentType()) ? "result.jpg" : "result.png";
        MultipartFile file = new ByteArrayMultipartFile(filename, detected.contentType(), bytes);
        try {
            imageUploadValidator.validate(file);
        } catch (ResponseStatusException e) {
            throw badRequest("媒体结果不是有效图片");
        }
        return detected;
    }

    private DetectedImage detectHeader(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, false, true);
                String contentType = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "png" -> "image/png";
                    default -> "";
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_OUTPUT_EDGE || height > MAX_OUTPUT_EDGE) {
                    return null;
                }
                return new DetectedImage(contentType, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体对象暂时不可用");
    }

    private record DetectedImage(String contentType, int width, int height) {
    }

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String filename;
        private final String contentType;
        private final byte[] bytes;

        private ByteArrayMultipartFile(String filename, String contentType, byte[] bytes) {
            this.filename = filename;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            try (OutputStream output = java.nio.file.Files.newOutputStream(destination.toPath())) {
                output.write(bytes);
            }
        }

        @Override
        public void transferTo(Path destination) throws IOException {
            java.nio.file.Files.write(destination, bytes);
        }
    }
}
