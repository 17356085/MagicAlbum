package com.example.demo.storage.service.provider.local;

import com.example.demo.storage.service.ImageUploadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageServiceTest {
    @TempDir Path root;

    @Test void preparationDoesNotWriteAndActualStoredBytesMatchDurableIntent() throws Exception {
        var service = service();
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        var prepared = service.prepareImage(new MockMultipartFile("file", "photo.png", "image/png", output.toByteArray()));
        try (var files = Files.list(root)) { assertThat(files.toList()).isEmpty(); }
        assertThat(prepared.intent().byteSize()).isEqualTo(output.size());
        assertThat(prepared.intent().sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray())));
        service.writeImage(prepared);
        assertThat(Files.readAllBytes(root.resolve(prepared.objectKey()))).isEqualTo(output.toByteArray());
        assertThat(prepared.url()).isEqualTo("/uploads/" + prepared.objectKey());
        assertThatThrownBy(() -> service.writeImage(prepared)).hasMessageContaining("上传失败");
        assertThat(Files.readAllBytes(root.resolve(prepared.objectKey()))).isEqualTo(output.toByteArray());
    }

    @Test void unsafeConfiguredPrefixFailsBeforeAnyWrite() throws Exception {
        var service = service();
        ReflectionTestUtils.setField(service, "pathPrefix", "../escape");
        assertThatThrownBy(() -> service.prepareImage(new MockMultipartFile("file", new byte[]{1})))
                .isInstanceOf(IllegalStateException.class);
        try (var files = Files.list(root)) { assertThat(files.toList()).isEmpty(); }
    }

    private LocalStorageService service() {
        var service = new LocalStorageService(new ImageUploadValidator());
        ReflectionTestUtils.setField(service, "baseDir", root.toString());
        ReflectionTestUtils.setField(service, "pathPrefix", "threads");
        return service;
    }
}
