package com.example.demo.storage.service.provider.s3;

import com.example.demo.storage.controller.StoredImageController;
import com.example.demo.storage.service.ImageUploadValidator;
import com.example.demo.storage.service.MediaObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3StorageServiceTest {
    private static final String MANAGED_KEY = "threads/2026/01/01/" + "a".repeat(32) + ".png";

    @Test void oversizedReadAbortsInsteadOfDrainingTheRemainingResponse() {
        var service = storage();
        var body = new SimulatedApacheBody(MediaObjectStore.MAX_BYTES + 1024 * 1024);
        stubRead(service, body);

        assertThatThrownBy(() -> service.readManagedObject(MANAGED_KEY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("图片存储暂不可用");
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(body.aborted).isTrue();
            soft.assertThat(body.closed).isTrue();
            soft.assertThat(body.bytesRead).isEqualTo(MediaObjectStore.MAX_BYTES + 1);
        });
    }

    @Test void failedReadAbortsAndKeepsFailureDetailsPrivate() {
        var service = storage();
        var body = new SimulatedApacheBody(100);
        body.failRead = true;
        stubRead(service, body);

        assertThatThrownBy(() -> service.readManagedObject(MANAGED_KEY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("图片存储暂不可用")
                .hasMessageNotContaining("private storage details");
        assertThat(body.aborted).isTrue();
        assertThat(body.closed).isTrue();
    }

    @Test void successfulReadClosesWithoutAborting() {
        var service = storage();
        var body = new SimulatedApacheBody(3);
        stubRead(service, body);

        assertThat(service.readManagedObject(MANAGED_KEY)).containsExactly((byte) 42, (byte) 42, (byte) 42);
        assertThat(body.aborted).isFalse();
        assertThat(body.closed).isTrue();
    }

    @Test void runtimeReadFailureAlsoAborts() {
        var service = storage();
        var body = new SimulatedApacheBody(100);
        body.failRuntime = true;
        stubRead(service, body);

        assertThatThrownBy(() -> service.readManagedObject(MANAGED_KEY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("private storage details");
        assertThat(body.aborted).isTrue();
        assertThat(body.closed).isTrue();
    }

    @Test void emptyObjectIsRejectedAndClosed() {
        var service = storage();
        var body = new SimulatedApacheBody(0);
        stubRead(service, body);

        assertThatThrownBy(() -> service.readManagedObject(MANAGED_KEY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("图片存储暂不可用");
        assertThat(body.aborted).isTrue();
        assertThat(body.closed).isTrue();
    }

    @SuppressWarnings("unchecked")
    private void stubRead(S3StorageService service, SimulatedApacheBody body) {
        var client = (S3Client) ReflectionTestUtils.getField(service, "client");
        var response = new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(body, body::abort));
        when(client.getObject(any(Consumer.class))).thenReturn(response);
    }

    /** Models the documented Apache close behavior; this is not a real network server. */
    private static final class SimulatedApacheBody extends InputStream {
        private long remaining;
        private long bytesRead;
        private boolean aborted;
        private boolean closed;
        private boolean failRead;
        private boolean failRuntime;

        private SimulatedApacheBody(long length) { this.remaining = length; }

        @Override public int read() throws IOException {
            if (failRuntime) throw new IllegalStateException("private storage details");
            if (failRead) throw new IOException("private storage details");
            if (remaining == 0) return -1;
            remaining--;
            bytesRead++;
            return 42;
        }

        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (failRuntime) throw new IllegalStateException("private storage details");
            if (failRead) throw new IOException("private storage details");
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, length);
            Arrays.fill(target, offset, offset + count, (byte) 42);
            remaining -= count;
            bytesRead += count;
            return count;
        }

        private void abort() { aborted = true; }

        @Override public void close() {
            closed = true;
            if (!aborted) {
                bytesRead += remaining;
                remaining = 0;
            }
        }
    }

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
