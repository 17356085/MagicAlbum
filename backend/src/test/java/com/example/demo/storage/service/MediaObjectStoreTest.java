package com.example.demo.storage.service;

import com.example.demo.storage.service.provider.s3.S3StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MediaObjectStoreTest {
    @TempDir Path root;

    @Test
    void localWriteIsImmutableAndCannotTraverseToOriginals() throws Exception {
        var objects = new MediaObjectStore(mock(S3StorageService.class), root.toString());
        var output = objects.planDerived("local", UUID.randomUUID().toString(), 1, "preview", "1", "a".repeat(64), "image/png");
        byte[] original = {4, 5, 6};
        objects.write(output, original);
        objects.write(output, original);
        assertThatThrownBy(() -> objects.write(output, new byte[]{7})).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(objects.readOriginal("local", output.objectKey())).isEqualTo(original);
        assertThatThrownBy(() -> objects.deleteDerived("local", "threads/2026/10/19/" + "a".repeat(32) + ".png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> objects.readOriginal("local", "../private")).isInstanceOf(IllegalArgumentException.class);
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(path -> path.getFileName().toString().contains(".tmp-")).toList()).isEmpty();
        }
    }

    @Test
    void boundedScannerProgressesPastFirstHundredEntriesAndRetainsYoungObjects() throws Exception {
        var objects = new MediaObjectStore(mock(S3StorageService.class), root.toString());
        var expected = new HashSet<String>();
        for (int index = 0; index < 120; index++) {
            var output = objects.planDerived("local", UUID.randomUUID().toString(), 1, "preview", "1", "a".repeat(64), "image/png");
            objects.write(output, new byte[]{1});
            Files.setLastModifiedTime(root.resolve(output.objectKey()), FileTime.from(Instant.now().minusSeconds(7200)));
            expected.add(output.objectKey());
        }
        var young = objects.planDerived("local", UUID.randomUUID().toString(), 1, "preview", "1", "b".repeat(64), "image/png");
        objects.write(young, new byte[]{2});
        var found = new HashSet<String>();
        try {
            for (int batch = 0; batch < 15; batch++) {
                var scanned = objects.scanDerived("local", Instant.now().minusSeconds(3600));
                assertThat(scanned.size()).isLessThanOrEqualTo(100);
                scanned.forEach(object -> found.add(object.objectKey()));
            }
        } finally { objects.closeScan(); }
        assertThat(found).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(found).doesNotContain(young.objectKey());
    }
}
