package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaAssetStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class MediaUploadServiceTest {
    private final StorageService storage = mock(StorageService.class);
    private final MediaUploadRegistrationService registry = mock(MediaUploadRegistrationService.class);
    private final MediaUploadService service = new MediaUploadService(storage, registry);
    private final MockMultipartFile file = new MockMultipartFile("file", new byte[]{1});

    @Test void defaultDisabledKeepsLegacyResponseAndAvoidsRegistry() {
        when(storage.uploadImage(file)).thenReturn("/uploads/old.png");
        assertThat(service.upload(7L, file)).containsExactlyEntriesOf(java.util.Map.of("url", "/uploads/old.png"));
        verifyNoInteractions(registry);
    }

    @Test void intentCommitsBeforeWriteAndReadyProducesAdditiveResponse() {
        var upload = prepared();
        when(registry.ready("asset")).thenReturn(new MediaAssetStatus("asset", "/uploads/image.png", "READY", "job", "PENDING"));
        assertThat(service.upload(7L, file)).containsEntry("url", "/uploads/image.png")
                .containsEntry("assetId", "asset").containsEntry("jobId", "job").containsEntry("processingStatus", "PENDING");
        var order = inOrder(storage, registry);
        order.verify(storage).prepareImage(file);
        order.verify(registry).begin(7L, upload.image().intent());
        order.verify(storage).writeImage(upload);
        order.verify(registry).ready("asset");
    }

    @Test void failedIntentNeverWritesExternalObject() {
        var upload = prepared();
        when(registry.begin(7L, upload.image().intent())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.upload(7L, file)).isInstanceOf(IllegalStateException.class);
        verify(storage, never()).writeImage(upload);
    }

    @Test void unconfirmedWriteLeavesIntentForReconciliationAndPropagatesOriginalFailure() {
        var upload = prepared();
        var failure = new IllegalStateException("write result uncertain");
        doThrow(failure).when(storage).writeImage(upload);
        assertThatThrownBy(() -> service.upload(7L, file)).isSameAs(failure);
        verify(registry, never()).failed("asset");
        verify(registry, never()).ready("asset");
    }

    @Test void failedReadyLeavesIntentForReconciliationInsteadOfMarkingWriteFailed() {
        prepared();
        when(registry.ready("asset")).thenThrow(new IllegalStateException("commit uncertain"));
        assertThatThrownBy(() -> service.upload(7L, file)).isInstanceOf(IllegalStateException.class);
        verify(registry, never()).failed("asset");
    }

    private StorageService.PreparedUpload prepared() {
        ReflectionTestUtils.setField(service, "registrationEnabled", true);
        var image = new PreparedImage("local", "threads/key.png", "/uploads/image.png", "image/png", "a".repeat(64), new byte[]{1});
        var upload = new StorageService.PreparedUpload(mock(ImageStorageProvider.class), image);
        when(storage.prepareImage(file)).thenReturn(upload);
        when(registry.begin(7L, image.intent())).thenReturn("asset");
        return upload;
    }
}
