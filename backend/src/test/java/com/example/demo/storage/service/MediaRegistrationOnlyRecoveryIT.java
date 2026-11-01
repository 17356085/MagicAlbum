package com.example.demo.storage.service;

import com.example.demo.Demo1Application;
import com.example.demo.storage.dto.MediaUploadIntent;
import com.example.demo.storage.entity.MediaAsset;
import com.example.demo.storage.service.provider.s3.S3StorageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real scheduler baseline for registration-only media uploads.
 *
 * <p>The stale-object recovery test is intentionally expected to fail against the old
 * implementation: registration is enabled while processing is disabled, so
 * stale uploads must still be reconciled but the old conditional excludes the
 * maintenance scheduler. The object write and registry transitions remain
 * real; only unrelated Redis access and RabbitMQ are isolated.</p>
 */
@SpringBootTest(
        classes = Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                "app.media.registration-enabled=true",
                "app.media.processing-enabled=false",
                "app.media.reconcile-delay-ms=50",
                "app.media.recovery-delay-ms=3600000",
                "app.media.cleanup-delay-ms=3600000",
                "app.rabbit.enabled=false",
                "spring.task.scheduling.enabled=true"
        })
@ActiveProfiles("test")
@DisabledInAotMode
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MediaRegistrationOnlyRecoveryIT.RecordingMediaObjectStoreConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MockitoBean(types = StringRedisTemplate.class)
class MediaRegistrationOnlyRecoveryIT {

    private static final String DATABASE_NAME = "media_registration_only_recovery_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final long OWNER_ID = 8201L;

    @TempDir
    static Path uploadRoot;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MediaUploadRegistrationService registry;

    @Autowired
    private RecordingMediaObjectStore objects;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false");
        properties.add("app.storage.local.baseDir", () -> uploadRoot.toString());
    }

    @BeforeAll
    static void verifyTemporaryTargetBeforeTests() throws IOException {
        verifiedUploadRoot();
    }

    @BeforeEach
    void clearRegistry() {
        jdbc.update("DELETE FROM media_request_outbox");
        jdbc.update("DELETE FROM media_processing_job");
        jdbc.update("DELETE FROM media_asset");
        objects.clearReadObservations();
    }

    @Test
    void registrationOnlyContextHasNoProcessingPublisher() {
        assertThat(environment.getProperty("app.media.registration-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.media.processing-enabled", Boolean.class)).isFalse();
        assertThat(applicationContext.getBeansOfType(MediaRequestPublisher.class)).isEmpty();
    }

    @Test
    void staleJpegAndPngObjectsAreRecoveredByTheRealScheduler() throws Exception {
        byte[] jpeg = tinyImage("jpg");
        byte[] png = tinyImage("png");
        RegisteredUpload jpegUpload = registerUpload("image/jpeg", "jpg", jpeg);
        RegisteredUpload pngUpload = registerUpload("image/png", "png", png);

        ageUpload(jpegUpload.assetId());
        ageUpload(pngUpload.assetId());

        assertThat(statusOf(jpegUpload.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(statusOf(pngUpload.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(countJobs()).isZero();
        assertThat(countOutbox()).isZero();

        writeOriginal(jpegUpload.intent().objectKey(), jpeg);
        writeOriginal(pngUpload.intent().objectKey(), png);

        awaitReady(List.of(jpegUpload.assetId(), pngUpload.assetId()));

        assertThat(statusOf(jpegUpload.assetId())).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(statusOf(pngUpload.assetId())).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(readOriginal(jpegUpload.intent().objectKey())).isEqualTo(jpeg);
        assertThat(readOriginal(pngUpload.intent().objectKey())).isEqualTo(png);
        assertThat(countJobsFor(jpegUpload.assetId())).isEqualTo(1L);
        assertThat(countOutboxFor(jpegUpload.assetId())).isEqualTo(1L);
        assertThat(countJobsFor(pngUpload.assetId())).isEqualTo(1L);
        assertThat(countOutboxFor(pngUpload.assetId())).isEqualTo(1L);
        assertThat(countJobs()).isEqualTo(2L);
        assertThat(countOutbox()).isEqualTo(2L);
        assertThat(applicationContext.getBeansOfType(MediaRequestPublisher.class)).isEmpty();
        awaitScheduledRead(jpegUpload.intent().objectKey());
        awaitScheduledRead(pngUpload.intent().objectKey());

        // A new missing object proves another scheduled pass ran after both
        // originals were recovered, before checking for duplicate writes.
        RegisteredUpload missingLater = registerUpload("image/jpeg", "jpg", jpeg);
        ageUpload(missingLater.assetId());
        awaitScheduledRead(missingLater.intent().objectKey());
        assertThat(statusOf(missingLater.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(countJobs()).isEqualTo(2L);
        assertThat(countOutbox()).isEqualTo(2L);
    }

    @Test
    void missingOrMismatchedObjectsRemainUploadingWithoutProcessingRows() throws Exception {
        byte[] actual = tinyImage("jpg");
        byte[] different = tinyImage("png");
        RegisteredUpload missing = registerUpload("image/jpeg", "jpg", actual);
        RegisteredUpload mismatched = registerUpload("image/png", "png", actual);

        ageUpload(missing.assetId());
        ageUpload(mismatched.assetId());

        writeOriginal(mismatched.intent().objectKey(), different);

        awaitUploading(List.of(missing.assetId(), mismatched.assetId()), Duration.ofMillis(750));

        awaitScheduledRead(missing.intent().objectKey());
        awaitScheduledRead(mismatched.intent().objectKey());

        assertThat(statusOf(missing.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(statusOf(mismatched.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(countJobs()).isZero();
        assertThat(countOutbox()).isZero();
        assertThat(readOriginal(mismatched.intent().objectKey())).isEqualTo(different);
    }

    private void awaitScheduledRead(String objectKey) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (objects.hasReadOnSchedulerThread(objectKey)) return;
            Thread.sleep(25L);
        }
        assertThat(objects.readThreads(objectKey))
                .as("read threads for %s", objectKey)
                .anyMatch(thread -> thread.startsWith("scheduled-media-storage-"));
    }

    private RegisteredUpload registerUpload(String contentType, String extension, byte[] bytes) {
        String leaf = UUID.randomUUID().toString().replace("-", "");
        String objectKey = "threads/registration-only/2026/10/29/" + leaf + "." + extension;
        MediaUploadIntent intent = new MediaUploadIntent(
                "local",
                objectKey,
                "/uploads/" + objectKey,
                contentType,
                bytes.length,
                MediaUploadReconciler.digest(bytes));
        return new RegisteredUpload(registry.begin(OWNER_ID, intent), intent);
    }

    private void ageUpload(String assetId) {
        jdbc.update("UPDATE media_asset SET updated_at=? WHERE asset_id=?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), assetId);
    }

    private void awaitReady(List<String> assetIds) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (statusesOf(assetIds).stream().allMatch(MediaAsset.STATUS_READY::equals)) return;
            Thread.sleep(25L);
        }
        assertThat(statusesOf(assetIds)).containsOnly(MediaAsset.STATUS_READY);
    }

    private void awaitUploading(List<String> assetIds, Duration duration) throws InterruptedException {
        Instant deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            assertThat(statusesOf(assetIds)).containsOnly(MediaAsset.STATUS_UPLOADING);
            Thread.sleep(25L);
        }
    }

    private byte[] tinyImage(String format) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private void writeOriginal(String objectKey, byte[] bytes) throws IOException {
        Path target = verifiedUploadRoot().resolve(objectKey).normalize();
        assertThat(target.startsWith(verifiedUploadRoot())).isTrue();
        Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
    }

    private byte[] readOriginal(String objectKey) throws IOException {
        Path target = verifiedUploadRoot().resolve(objectKey).normalize();
        assertThat(target.startsWith(verifiedUploadRoot())).isTrue();
        return Files.readAllBytes(target);
    }

    private String statusOf(String assetId) {
        return jdbc.queryForObject("SELECT status FROM media_asset WHERE asset_id=?", String.class, assetId);
    }

    private List<String> statusesOf(List<String> assetIds) {
        return assetIds.stream().map(this::statusOf).toList();
    }

    private long countJobs() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_job", Long.class);
    }

    private long countOutbox() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM media_request_outbox", Long.class);
    }

    private long countJobsFor(String assetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_processing_job WHERE asset_id=?", Long.class, assetId);
    }

    private long countOutboxFor(String assetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_request_outbox WHERE asset_id=?", Long.class, assetId);
    }

    private static Path verifiedUploadRoot() throws IOException {
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path actualRoot = uploadRoot.toRealPath();
        assertThat(actualRoot.startsWith(temporaryRoot)).isTrue();
        assertThat(actualRoot).isNotEqualTo(temporaryRoot);
        return actualRoot;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingMediaObjectStoreConfiguration {
        @Bean
        @Primary
        RecordingMediaObjectStore recordingMediaObjectStore(
                S3StorageService s3,
                @Value("${app.storage.local.baseDir:uploads}") String root) {
            return new RecordingMediaObjectStore(s3, root);
        }
    }

    static final class RecordingMediaObjectStore extends MediaObjectStore {
        private final ConcurrentMap<String, Set<String>> readThreadsByKey = new ConcurrentHashMap<>();

        RecordingMediaObjectStore(S3StorageService s3, String root) {
            super(s3, root);
        }

        @Override
        public byte[] readOriginal(String provider, String objectKey) {
            readThreadsByKey.computeIfAbsent(objectKey, ignored -> ConcurrentHashMap.newKeySet())
                    .add(Thread.currentThread().getName());
            return super.readOriginal(provider, objectKey);
        }

        void clearReadObservations() {
            readThreadsByKey.clear();
        }

        Set<String> readThreads(String objectKey) {
            Set<String> threads = readThreadsByKey.get(objectKey);
            return threads == null ? Set.of() : Set.copyOf(threads);
        }

        boolean hasReadOnSchedulerThread(String objectKey) {
            return readThreads(objectKey).stream()
                    .anyMatch(thread -> thread.startsWith("scheduled-media-storage-"));
        }
    }

    private record RegisteredUpload(String assetId, MediaUploadIntent intent) {
    }
}
