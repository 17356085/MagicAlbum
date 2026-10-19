package com.example.demo.storage.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageUploadValidatorTest {

    private static final long MAX_SIZE = 10L * 1024 * 1024;

    private final ImageUploadValidator validator = new ImageUploadValidator();

    @Test
    void acceptsRealPngJpegAndGifImages() throws Exception {
        assertAccepted("png", MediaType.IMAGE_PNG_VALUE, "avatar.png");
        assertAccepted("jpeg", MediaType.IMAGE_JPEG_VALUE, "avatar.jpg");
        assertAccepted("gif", MediaType.IMAGE_GIF_VALUE, "avatar.gif");
    }

    @Test
    void acceptsJpegExtensionAlias() throws Exception {
        byte[] jpeg = imageBytes("jpeg");
        ImageUploadValidator.ValidatedImage validated = validator.validate(
                new MockMultipartFile("file", "avatar.jpeg", MediaType.IMAGE_JPEG_VALUE, jpeg));

        assertThat(validated.extension()).isEqualTo(".jpeg");
        assertThat(validated.bytes()).containsExactly(jpeg);
    }

    @Test
    void rejectsMimeAndExtensionSpoofing() throws Exception {
        byte[] png = imageBytes("png");
        byte[] jpeg = imageBytes("jpeg");

        assertRejected(new MockMultipartFile("file", "avatar.jpg", MediaType.IMAGE_JPEG_VALUE, png));
        assertRejected(new MockMultipartFile("file", "avatar.png", MediaType.IMAGE_PNG_VALUE, jpeg));
        assertRejected(new MockMultipartFile("file", "avatar.png", MediaType.IMAGE_JPEG_VALUE, png));
    }

    @Test
    void readsActualBytesThroughInputStreamWithoutCallingGetBytes() throws Exception {
        byte[] png = imageBytes("png");
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(0L);
        when(file.getContentType()).thenReturn(MediaType.IMAGE_PNG_VALUE);
        when(file.getOriginalFilename()).thenReturn("avatar.png");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(png));
        when(file.getBytes()).thenThrow(new AssertionError("getBytes must not be used"));

        ImageUploadValidator.ValidatedImage validated = validator.validate(file);

        assertThat(validated.bytes()).containsExactly(png);
        verify(file, never()).getBytes();
    }

    @Test
    void rejectsActualOversizeEvenWhenReportedSizeIsSmall() throws Exception {
        byte[] oversize = new byte[(int) MAX_SIZE + 1];
        Arrays.fill(oversize, (byte) 'x');
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(0L);
        when(file.getContentType()).thenReturn(MediaType.IMAGE_PNG_VALUE);
        when(file.getOriginalFilename()).thenReturn("avatar.png");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(oversize));

        ResponseStatusException error = assertThrowsBadRequest(file);

        assertThat(error.getReason()).isEqualTo("图片大小不能超过10MB");
    }

    @Test
    void rejectsDeclaredOversizeBeforeOpeningInput() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(MAX_SIZE + 1);

        ResponseStatusException error = assertThrowsBadRequest(file);

        assertThat(error.getReason()).isEqualTo("图片大小不能超过10MB");
        verify(file, never()).getInputStream();
    }

    @Test
    void rejectsHugeDimensionsFromReaderHeaderBeforeDecode() {
        byte[] hugeEdgePng = pngHeader(20_001, 1);
        byte[] hugePixelsPng = pngHeader(6_000, 4_001);

        ResponseStatusException edgeError = assertThrowsBadRequest(
                new MockMultipartFile("file", "huge.png", MediaType.IMAGE_PNG_VALUE, hugeEdgePng));
        ResponseStatusException pixelsError = assertThrowsBadRequest(
                new MockMultipartFile("file", "huge.png", MediaType.IMAGE_PNG_VALUE, hugePixelsPng));

        assertThat(edgeError.getReason()).isEqualTo("图片尺寸超过处理限制");
        assertThat(pixelsError.getReason()).isEqualTo("图片尺寸超过处理限制");
    }

    @Test
    void rejectsEmptyAndTruncatedPayloads() {
        ResponseStatusException empty = assertThrowsBadRequest(
                new MockMultipartFile("file", "empty.png", MediaType.IMAGE_PNG_VALUE, new byte[0]));
        assertThat(empty.getReason()).isEqualTo("文件不能为空");
        assertRejected(new MockMultipartFile(
                "file", "truncated.png", MediaType.IMAGE_PNG_VALUE,
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
    }

    @Test
    void rejectsReadErrorsWithoutExposingUnderlyingDetails() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getContentType()).thenReturn(MediaType.IMAGE_PNG_VALUE);
        when(file.getOriginalFilename()).thenReturn("avatar.png");
        when(file.getInputStream()).thenThrow(new IOException("private storage path"));

        ResponseStatusException error = assertThrowsBadRequest(file);

        assertThat(error.getReason()).isEqualTo("读取上传文件失败");
        assertThat(error.getMessage()).doesNotContain("private storage path");
        assertThat(error.getCause()).isNull();
    }

    private void assertAccepted(String format, String contentType, String filename) throws Exception {
        byte[] bytes = imageBytes(format);
        ImageUploadValidator.ValidatedImage validated = validator.validate(
                new MockMultipartFile("file", filename, contentType, bytes));

        assertThat(validated.contentType()).isEqualTo(contentType);
        assertThat(validated.bytes()).containsExactly(bytes);
    }

    private byte[] imageBytes(String format) throws IOException {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        image.setRGB(1, 0, Color.GREEN.getRGB());
        image.setRGB(2, 0, Color.BLUE.getRGB());
        image.setRGB(0, 1, Color.WHITE.getRGB());
        image.setRGB(1, 1, Color.BLACK.getRGB());
        image.setRGB(2, 1, Color.YELLOW.getRGB());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private ResponseStatusException assertThrowsBadRequest(MultipartFile file) {
        Throwable error = catchThrowable(() -> validator.validate(file));
        assertThat(error).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException responseStatusException = (ResponseStatusException) error;
        assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return responseStatusException;
    }

    private void assertRejected(MultipartFile file) {
        assertThrowsBadRequest(file);
    }

    private byte[] pngHeader(int width, int height) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeInt(chunk, width);
        writeInt(chunk, height);
        chunk.writeBytes(new byte[]{8, 2, 0, 0, 0});
        byte[] chunkData = chunk.toByteArray();

        writeInt(output, chunkData.length);
        output.writeBytes("IHDR".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(chunkData);
        CRC32 crc = new CRC32();
        crc.update("IHDR".getBytes(StandardCharsets.US_ASCII));
        crc.update(chunkData);
        writeInt(output, (int) crc.getValue());
        return output.toByteArray();
    }

    private void writeInt(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }
}
