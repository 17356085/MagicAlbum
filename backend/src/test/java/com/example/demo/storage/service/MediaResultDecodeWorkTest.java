package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaExecutionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Work harness for measuring the original coordinator/validator duplicate
 * full-image decode. The expected happy-path count is one; the unchanged
 * implementation currently reports two (detect plus ImageUploadValidator).
 */
class MediaResultDecodeWorkTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String INPUT_SHA = "b".repeat(64);
    private static final String PROCESSOR_VERSION = "1";

    @Test
    void pngHappyPathHasSingleFullImageDecode() throws Exception {
        byte[] output = encodedImage("png", 4, 3);
        Harness harness = harness(output, "image/png");

        try (DecodeCounter counter = DecodeCounter.open()) {
            MediaExecutionResponse response = harness.coordinator().submit(
                    harness.jobId(), output, 1, INPUT_SHA, harness.outputSha(), PROCESSOR_VERSION,
                    4, 3, harness.leaseToken());
            report("png-happy", counter.readCount(), 1);

            assertThat(response.status()).isEqualTo(MediaJobService.STATUS_SUCCEEDED);
            verifySuccessfulWrite(harness, output, "image/png");
            assertThat(counter.readCount()).isEqualTo(1);
        }
    }

    @Test
    void jpegHappyPathHasSingleFullImageDecode() throws Exception {
        byte[] output = encodedImage("jpeg", 4, 3);
        Harness harness = harness(output, "image/jpeg");

        try (DecodeCounter counter = DecodeCounter.open()) {
            MediaExecutionResponse response = harness.coordinator().submit(
                    harness.jobId(), output, 1, INPUT_SHA, harness.outputSha(), PROCESSOR_VERSION,
                    4, 3, harness.leaseToken());
            report("jpeg-happy", counter.readCount(), 1);

            assertThat(response.status()).isEqualTo(MediaJobService.STATUS_SUCCEEDED);
            verifySuccessfulWrite(harness, output, "image/jpeg");
            assertThat(counter.readCount()).isEqualTo(1);
        }
    }

    @Test
    void truncatedPixelsWithValidPngHeaderAreRejectedAfterFullReadBeforePlanOrWrite() throws Exception {
        byte[] output = truncatedPngPixels(4, 3);
        Harness harness = harness(output, "image/png");

        try (DecodeCounter counter = DecodeCounter.open()) {
            ResponseStatusException error = assertBadRequest(() -> harness.coordinator().submit(
                    harness.jobId(), output, 1, INPUT_SHA, harness.outputSha(), PROCESSOR_VERSION,
                    4, 3, harness.leaseToken()));
            report("png-truncated", counter.readCount(), 1);

            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(counter.readCount()).isEqualTo(1);
        }

        verify(harness.objectStore(), never()).planDerived(
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(harness.objectStore(), never()).write(any(), any());
    }

    @Test
    void actualOversizedImageIsRejectedBeforeFullReadEvenWhenReportedWidthFits() throws Exception {
        byte[] output = encodedImage("png", 1601, 1);
        Harness harness = harness(output, "image/png");

        try (DecodeCounter counter = DecodeCounter.open()) {
            ResponseStatusException error = assertBadRequest(() -> harness.coordinator().submit(
                    harness.jobId(), output, 1, INPUT_SHA, harness.outputSha(), PROCESSOR_VERSION,
                    1600, 1, harness.leaseToken()));
            report("png-oversized-header", counter.readCount(), 0);

            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(counter.readCount()).isZero();
        }

        verify(harness.objectStore(), never()).planDerived(
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(harness.objectStore(), never()).write(any(), any());
    }

    private Harness harness(byte[] output, String contentType) throws Exception {
        String assetId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String leaseToken = "lease-token-" + UUID.randomUUID();
        String outputSha = sha256(output);
        String objectKey = "media/" + assetId + "/v1/preview/p1/" + outputSha
                + ("image/jpeg".equals(contentType) ? ".jpg" : ".png");
        MediaObjectStore.StoredObject stored = new MediaObjectStore.StoredObject(
                "local", objectKey, "/uploads/" + objectKey);

        MediaJobService jobService = mock(MediaJobService.class);
        ImageUploadValidator imageUploadValidator = new ImageUploadValidator();
        MediaObjectStore objectStore = mock(MediaObjectStore.class);
        MediaJobService.JobLeaseSnapshot snapshot = new MediaJobService.JobLeaseSnapshot(
                jobId, assetId, 1, "preview", PROCESSOR_VERSION, MediaJobService.STATUS_PROCESSING,
                null, Instant.now().plusSeconds(60), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "local", INPUT_SHA);

        when(jobService.lockForResult(jobId, 1, leaseToken)).thenReturn(snapshot);
        when(jobService.reserveResult(eq(jobId), eq(1), eq(leaseToken), any()))
                .thenAnswer(invocation -> reservationFor(invocation));
        when(jobService.acceptResult(eq(jobId), eq(1), eq(leaseToken), any()))
                .thenReturn(new MediaExecutionResponse(MediaJobService.STATUS_SUCCEEDED));
        when(objectStore.planDerived(
                "local", assetId, 1, "preview", PROCESSOR_VERSION, outputSha, contentType))
                .thenReturn(stored);
        doNothing().when(objectStore).write(stored, output);

        return new Harness(
                jobId, assetId, leaseToken, outputSha,
                new MediaResultCoordinator(jobService, imageUploadValidator, objectStore),
                objectStore, stored);
    }

    private MediaJobService.Reservation reservationFor(InvocationOnMock invocation) {
        MediaJobService.CandidateMetadata candidate = invocation.getArgument(3);
        return new MediaJobService.Reservation(false, candidate);
    }

    private void verifySuccessfulWrite(Harness harness, byte[] output, String contentType) throws Exception {
        verify(harness.objectStore()).planDerived(
                "local", harness.assetId(), 1, "preview", PROCESSOR_VERSION,
                harness.outputSha(), contentType);
        verify(harness.objectStore()).write(same(harness.stored()), same(output));
    }

    private ResponseStatusException assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable error = catchThrowable(action);
        assertThat(error).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException response = (ResponseStatusException) error;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return response;
    }

    private byte[] encodedImage(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? 0xFF336699 : 0xFFCC8844);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, bytes)).isTrue();
        return bytes.toByteArray();
    }

    private byte[] truncatedPngPixels(int width, int height) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        });
        writePngChunk(bytes, "IHDR", pngIhdr(width, height));
        writePngChunk(bytes, "IDAT", new byte[]{0x78});
        return bytes.toByteArray();
    }

    private byte[] pngIhdr(int width, int height) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeInt(bytes, width);
        writeInt(bytes, height);
        bytes.writeBytes(new byte[]{8, 2, 0, 0, 0});
        return bytes.toByteArray();
    }

    private void writePngChunk(ByteArrayOutputStream output, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(output, data.length);
        output.writeBytes(typeBytes);
        output.writeBytes(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(output, (int) crc.getValue());
    }

    private void writeInt(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void report(String scenario, int actual, int expected) {
        System.out.printf("MEDIA_RESULT_DECODE_WORK case=%s fullImageReadCount=%d expected=%d%n",
                scenario, actual, expected);
    }

    private record Harness(
            String jobId,
            String assetId,
            String leaseToken,
            String outputSha,
            MediaResultCoordinator coordinator,
            MediaObjectStore objectStore,
            MediaObjectStore.StoredObject stored
    ) {}

    private static final class DecodeCounter implements AutoCloseable {
        private final AtomicInteger readCount = new AtomicInteger();
        private final MockedStatic<ImageIO> imageIo;

        private DecodeCounter() {
            imageIo = mockStatic(ImageIO.class, CALLS_REAL_METHODS);
            imageIo.when(() -> ImageIO.getImageReaders(any(Object.class)))
                    .thenAnswer(invocation -> wrappingIterator(invocation, readCount));
        }

        private static DecodeCounter open() {
            return new DecodeCounter();
        }

        private static Iterator<ImageReader> wrappingIterator(
                InvocationOnMock invocation, AtomicInteger readCount) throws Throwable {
            @SuppressWarnings("unchecked")
            Iterator<ImageReader> readers = (Iterator<ImageReader>) invocation.callRealMethod();
            return new CountingIterator(readers, readCount);
        }

        private int readCount() {
            return readCount.get();
        }

        @Override
        public void close() {
            imageIo.close();
        }
    }

    private static final class CountingIterator implements Iterator<ImageReader> {
        private final Iterator<ImageReader> delegates;
        private final AtomicInteger readCount;

        private CountingIterator(Iterator<ImageReader> delegates, AtomicInteger readCount) {
            this.delegates = delegates;
            this.readCount = readCount;
        }

        @Override
        public boolean hasNext() {
            return delegates.hasNext();
        }

        @Override
        public ImageReader next() {
            return new CountingImageReader(delegates.next(), readCount);
        }

        @Override
        public void remove() {
            delegates.remove();
        }
    }

    private static final class CountingImageReader extends ImageReader {
        private final ImageReader delegate;
        private final AtomicInteger readCount;

        private CountingImageReader(ImageReader delegate, AtomicInteger readCount) {
            super(delegate.getOriginatingProvider());
            this.delegate = delegate;
            this.readCount = readCount;
        }

        @Override
        public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
            delegate.setInput(input, seekForwardOnly, ignoreMetadata);
        }

        @Override
        public int getNumImages(boolean allowSearch) throws IOException {
            return delegate.getNumImages(allowSearch);
        }

        @Override
        public int getWidth(int imageIndex) throws IOException {
            return delegate.getWidth(imageIndex);
        }

        @Override
        public int getHeight(int imageIndex) throws IOException {
            return delegate.getHeight(imageIndex);
        }

        @Override
        public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
            return delegate.getImageTypes(imageIndex);
        }

        @Override
        public IIOMetadata getStreamMetadata() throws IOException {
            return delegate.getStreamMetadata();
        }

        @Override
        public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
            return delegate.getImageMetadata(imageIndex);
        }

        @Override
        public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
            readCount.incrementAndGet();
            return delegate.read(imageIndex, param);
        }

        @Override
        public void dispose() {
            delegate.dispose();
        }
    }
}
