package com.example.demo.storage.controller;

import com.example.demo.storage.dto.MediaClaimResponse;
import com.example.demo.storage.dto.MediaExecutionResponse;
import com.example.demo.storage.dto.MediaFailureRequest;
import com.example.demo.storage.dto.MediaInputAuthorization;
import com.example.demo.storage.dto.MediaRequestEnvelope;
import com.example.demo.storage.dto.MediaRenewRequest;
import com.example.demo.storage.dto.MediaRenewResponse;
import com.example.demo.storage.service.MediaJobService;
import com.example.demo.storage.service.MediaObjectStore;
import com.example.demo.storage.service.MediaResultCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Internal worker callbacks. Owner actions use the ordinary authenticated API. */
@RestController
@RequestMapping
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaInternalController {

    private static final HexFormat HEX = HexFormat.of();

    private final MediaJobService jobService;
    private final MediaResultCoordinator resultCoordinator;
    private final MediaObjectStore objectStore;

    public MediaInternalController(
            MediaJobService jobService,
            MediaResultCoordinator resultCoordinator,
            MediaObjectStore objectStore
    ) {
        this.jobService = jobService;
        this.resultCoordinator = resultCoordinator;
        this.objectStore = objectStore;
    }

    @PostMapping("/internal/media/v1/jobs/{jobId}/claim")
    public ResponseEntity<MediaClaimResponse> claim(
            @PathVariable String jobId,
            @RequestBody MediaRequestEnvelope envelope
    ) {
        return json(jobService.claim(jobId, envelope));
    }

    @PostMapping("/internal/media/v1/jobs/{jobId}/renew")
    public ResponseEntity<MediaRenewResponse> renew(
            @PathVariable String jobId,
            @RequestBody MediaRenewRequest request
    ) {
        return json(jobService.renew(jobId, request));
    }

    @GetMapping("/internal/media/v1/jobs/{jobId}/input")
    public ResponseEntity<byte[]> input(
            @PathVariable String jobId,
            @RequestParam int jobVersion,
            @RequestHeader("X-Media-Lease-Token") String leaseToken
    ) {
        MediaInputAuthorization authorization = jobService.authorizeInput(jobId, jobVersion, leaseToken);
        byte[] bytes;
        try {
            bytes = objectStore.readOriginal(authorization.provider(), authorization.objectKey());
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (bytes == null || bytes.length != authorization.byteSize()
                || !authorization.sha256().equals(sha256(bytes))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "媒体原图校验失败");
        }
        jobService.authorizeInput(jobId, jobVersion, leaseToken);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(authorization.contentType());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "媒体原图校验失败");
        }
        HttpHeaders headers = noStoreHeaders();
        headers.setContentType(mediaType);
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    @PostMapping(value = "/internal/media/v1/jobs/{jobId}/result", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaExecutionResponse> result(
            @PathVariable String jobId,
            @RequestPart("file") MultipartFile file,
            @RequestParam int jobVersion,
            @RequestParam String inputSha256,
            @RequestParam String outputSha256,
            @RequestParam String processorVersion,
            @RequestParam int width,
            @RequestParam int height,
            @RequestHeader("X-Media-Lease-Token") String leaseToken
    ) {
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "媒体结果文件不能为空");
        }
        byte[] bytes;
        try {
            try (var input = file.getInputStream()) {
                bytes = MediaObjectStore.readBounded(input);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "媒体结果文件读取失败");
        }
        return json(resultCoordinator.submit(
                jobId, bytes, jobVersion, inputSha256, outputSha256,
                processorVersion, width, height, leaseToken));
    }

    @PostMapping("/internal/media/v1/jobs/{jobId}/failure")
    public ResponseEntity<MediaExecutionResponse> failure(
            @PathVariable String jobId,
            @RequestBody MediaFailureRequest request
    ) {
        return json(jobService.fail(jobId, request));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", exception.getStatusCode().value());
        body.put("message", exception.getReason() == null ? "请求失败" : exception.getReason());
        return new ResponseEntity<>(body, noStoreHeaders(), exception.getStatusCode());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件不能超过 10MB");
    }

    private <T> ResponseEntity<T> json(T body) {
        return new ResponseEntity<>(body, noStoreHeaders(), HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("message", message);
        return new ResponseEntity<>(body, noStoreHeaders(), status);
    }

    private HttpHeaders noStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体对象暂时不可用");
    }

    private String sha256(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
