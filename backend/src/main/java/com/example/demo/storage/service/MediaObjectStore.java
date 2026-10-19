package com.example.demo.storage.service;

import com.example.demo.storage.service.provider.s3.S3StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.stream.Stream;

/** Only server-generated, canonical object identities cross this boundary. */
@Service
public class MediaObjectStore {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final String DERIVED_PATTERN = "media/" + UUID_PATTERN + "/v[1-9][0-9]*/preview/p1/[0-9a-f]{64}\\.(jpg|png)";
    private final S3StorageService s3;
    private final Path configuredRoot;

    public MediaObjectStore(S3StorageService s3, @Value("${app.storage.local.baseDir:uploads}") String root) {
        this.s3 = s3;
        this.configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public byte[] readOriginal(String provider, String objectKey) {
        requireManagedKey(objectKey);
        if ("s3".equals(provider)) return s3.readManagedObject(objectKey);
        requireLocal(provider);
        try (InputStream input = Files.newInputStream(localPath(objectKey, false), LinkOption.NOFOLLOW_LINKS)) {
            return readBounded(input);
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    public StoredObject planDerived(String provider, String assetId, int version, String preset,
                                    String processorVersion, String sha256, String type) {
        if (assetId == null || !assetId.matches(UUID_PATTERN) || version < 1 || !"preview".equals(preset)
                || !"1".equals(processorVersion) || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || !("image/jpeg".equals(type) || "image/png".equals(type))) {
            throw new IllegalArgumentException("Invalid derived image identity");
        }
        String key = "media/" + assetId + "/v" + version + "/" + preset + "/p" + processorVersion + "/"
                + sha256 + ("image/jpeg".equals(type) ? ".jpg" : ".png");
        if ("s3".equals(provider)) return new StoredObject(provider, key, s3.managedUrl(key));
        requireLocal(provider);
        return new StoredObject(provider, key, "/uploads/" + key);
    }

    public void write(StoredObject object, byte[] bytes) {
        requireDerivedKey(object.objectKey());
        if (bytes == null || bytes.length < 1 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid output size");
        String type = object.objectKey().endsWith(".png") ? "image/png" : "image/jpeg";
        if ("s3".equals(object.provider())) {
            s3.writeManagedObject(object.objectKey(), type, bytes);
            return;
        }
        requireLocal(object.provider());
        Path temporary = null;
        try {
            Path target = localPath(object.objectKey(), true);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireSameBytes(target, bytes);
                return;
            }
            temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException concurrent) {
                requireSameBytes(target, bytes);
            }
        } catch (IOException failure) {
            throw unavailable();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Aged temporary cleanup is safe. */ }
            }
        }
    }

    private Stream<Path> localScan;
    private Iterator<Path> localIterator;

    public synchronized List<ObjectAge> scanDerived(String provider, Instant olderThan) {
        if ("s3".equals(provider)) return s3.scanManagedObjects().stream()
                .filter(object -> object.modifiedAt().isBefore(olderThan)).toList();
        requireLocal(provider);
        if (!Files.isDirectory(configuredRoot.resolve("media"), LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            if (localScan == null) {
                localScan = Files.walk(configuredRoot.resolve("media"));
                localIterator = localScan.iterator();
            }
            List<ObjectAge> result = new ArrayList<>();
            for (int inspected = 0; inspected < 100 && localIterator.hasNext(); inspected++) {
                Path path = localIterator.next();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                ObjectAge object = age(path);
                if (object != null && object.modifiedAt().isBefore(olderThan)) result.add(object);
            }
            if (!localIterator.hasNext()) closeScan();
            return List.copyOf(result);
        } catch (IOException | java.io.UncheckedIOException failure) {
            closeScan();
            throw unavailable();
        }
    }

    @jakarta.annotation.PreDestroy
    public synchronized void closeScan() {
        if (localScan != null) localScan.close();
        localScan = null;
        localIterator = null;
    }

    public void deleteDerived(String provider, String key) {
        String canonical = key.replaceFirst("\\.tmp-[0-9a-f-]{36}$", "");
        requireDerivedKey(canonical);
        if ("s3".equals(provider)) { s3.deleteManagedObject(key); return; }
        requireLocal(provider);
        try { Files.deleteIfExists(localPath(key, false)); } catch (IOException failure) { throw unavailable(); }
    }

    private ObjectAge age(Path path) {
        String key = configuredRoot.relativize(path).toString().replace('\\', '/');
        try {
            requireDerivedKey(key.replaceFirst("\\.tmp-[0-9a-f-]{36}$", ""));
            return new ObjectAge(key, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
        } catch (IOException | IllegalArgumentException invalid) { return null; }
    }

    private Path localPath(String key, boolean create) throws IOException {
        if (create) Files.createDirectories(configuredRoot);
        Path root = configuredRoot.toRealPath();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IOException("Invalid object location");
        Path directory = root;
        for (Path segment : root.relativize(target.getParent())) {
            directory = directory.resolve(segment);
            if (create) {
                try { Files.createDirectory(directory); }
                catch (FileAlreadyExistsException existing) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw existing;
                }
            } else if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing object directory");
        }
        return target;
    }

    private static void requireSameBytes(Path target, byte[] expected) throws IOException {
        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(readBounded(input), expected)) throw new IOException("Conflicting immutable object");
        }
    }

    public static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Object size outside limits");
        return bytes;
    }

    public static void requireManagedKey(String key) {
        if (key == null || !(key.matches("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*/[0-9]{4}/[0-9]{2}/[0-9]{2}/[a-f0-9]{32}\\.(jpg|jpeg|png|gif|webp)")
                || key.matches(DERIVED_PATTERN))) throw new IllegalArgumentException("Invalid managed object key");
    }

    public static void requireDerivedKey(String key) {
        if (key == null || !key.matches(DERIVED_PATTERN)) throw new IllegalArgumentException("Invalid derived object key");
    }

    private static void requireLocal(String provider) {
        if (!"local".equals(provider)) throw new IllegalArgumentException("Unknown media provider");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片存储暂不可用");
    }

    public record StoredObject(String provider, String objectKey, String url) {}
    public record ObjectAge(String objectKey, Instant modifiedAt) {}
}
