package com.example.demo.storage.service.provider.s3;

import com.example.demo.storage.controller.StoredImageController;
import com.example.demo.storage.service.ImageUploadValidator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3StorageServiceTest {
    @Test void preparationHasNoExternalEffectAndWriteUsesExactPlannedKeyAndBytes() {
        var service = storage();
        var client = (S3Client) ReflectionTestUtils.getField(service, "client");
        var image = service.prepareImage(new MockMultipartFile("file", new byte[]{1}));
        verifyNoInteractions(client);
        assertThat(image.intent().provider()).isEqualTo("s3");
        assertThat(image.intent().byteSize()).isEqualTo(1);
        assertThat(image.intent().sha256()).isEqualTo("4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");
        var request = org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.PutObjectRequest.class);
        var body = org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.core.sync.RequestBody.class);
        service.writeImage(image);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().key()).isEqualTo(image.objectKey());
        assertThat(request.getValue().contentType()).isEqualTo(image.contentType());
        assertThat(body.getValue().contentLength()).isEqualTo(1);
    }

    private S3StorageService storage() {
        var validator = mock(ImageUploadValidator.class);
        when(validator.validate(any())).thenReturn(new ImageUploadValidator.ValidatedImage(new byte[]{1}, "image/jpeg", ".jpeg"));
        var service = new S3StorageService(validator);
        ReflectionTestUtils.setField(service, "bucket", "test-private-bucket");
        ReflectionTestUtils.setField(service, "region", "us-east-1");
        ReflectionTestUtils.setField(service, "accessKey", "test-access-key");
        ReflectionTestUtils.setField(service, "secretKey", "test-only-secret");
        ReflectionTestUtils.setField(service, "pathPrefix", "threads");
        ReflectionTestUtils.setField(service, "client", mock(S3Client.class));
        return service;
    }

    @Test void privateUploadReturnsStableUrlAndReadSignsAnExactGeneratedKey() {
        var service = storage();
        String url = service.uploadImage(new MockMultipartFile("file", new byte[]{1}));
        assertThat(url).startsWith("/uploads/s3/").doesNotContain("Signature");
        String token = url.substring("/uploads/s3/".length());
        var response = new StoredImageController(service).read(token);
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getLocation().getQuery()).contains("X-Amz-Signature=", "X-Amz-Expires=300");
        assertThat(response.getHeaders().getLocation().getPath()).endsWith(".jpeg");
    }

    @Test void cannotSignArbitraryBucketPathsOrTraversal() {
        var service = storage();
        for (String key : new String[]{"private/secret.txt", "threads/../../secret", "threads/2026/01/01/not-a-generated-key.png"}) {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> service.resolveReadUrl(token)).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test void explicitPublicCdnIsPreservedAndCustomEndpointsUseBucketPath() {
        var service = storage();
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://cdn.example.test/");
        assertThat(service.uploadImage(new MockMultipartFile("file", new byte[]{1}))).startsWith("https://cdn.example.test/threads/");
        ReflectionTestUtils.setField(service, "endpoint", "https://s3.example.test");
        String key = "threads/2026/01/01/" + "a".repeat(32) + ".png";
        var signed = service.resolveReadUrl(Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)));
        assertThat(signed.getHost()).isEqualTo("s3.example.test");
        assertThat(signed.getPath()).isEqualTo("/test-private-bucket/" + key);
    }
}
