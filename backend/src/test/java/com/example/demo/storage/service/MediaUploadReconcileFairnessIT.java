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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Evidence-first regression coverage for the bounded stale-upload reconciler.
 *
 * <p>The test deliberately instantiates the same maintenance service with a
 * real JDBC template, registration service and local object store. The Spring
 * component is disabled through processing=false, so no scheduled maintenance
 * can change the rows while this test observes the two explicit passes.</p>
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
                "app.rabbit.enabled=false",
                "spring.task.scheduling.enabled=false"
        })
@ActiveProfiles("test")
@DisabledInAotMode
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MockitoBean(types = StringRedisTemplate.class)
class MediaUploadReconcileFairnessIT {

    private static final String DATABASE_NAME = "media_reconcile_fairness_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final String VALID_CONTENT_TYPE = "image/jpeg";
    private static final long OWNER_ID = 7001L;

    @TempDir
    static Path uploadRoot;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MediaUploadRegistrationService registry;

    private MediaMaintenance maintenance;
    private MediaObjectStore objects;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false");
        properties.add("app.storage.local.baseDir", () -> uploadRoot.toString());
    }

    @BeforeAll
    static void verifyTemporaryTargetBeforeTestsAndCleanup() throws IOException {
        verifiedUploadRoot();
    }

    @BeforeEach
    void resetDatabaseAndMaintenance() throws IOException {
        verifiedUploadRoot();
        jdbc.update("DELETE FROM media_request_outbox");
        jdbc.update("DELETE FROM media_processing_job");
        jdbc.update("DELETE FROM media_asset");
        objects = spy(new MediaObjectStore(mock(S3StorageService.class), verifiedUploadRoot().toString()));
        maintenance = new MediaMaintenance(
                jdbc,
                mock(MediaDeliveryTransactions.class),
                registry,
                objects,
                mock(MediaMaintenanceTransactions.class),
                Duration.ofHours(24),
                true);
    }

    @Test
    void unreadableFirstTwentyDoNotStarveTheTwentyFirstValidOriginal() throws Exception {
        byte[] validBytes = tinyJpeg();
        String validDigest = MediaMaintenance.digest(validBytes);
        List<RegisteredUpload> uploads = registerUploads("fairness", 21, validBytes.length, validDigest);
        Instant oldUpdatedAt = Instant.now().minus(Duration.ofMinutes(10));
        setUpdatedAt(uploads.subList(0, 20), oldUpdatedAt);
        setUpdatedAt(List.of(uploads.getLast()), oldUpdatedAt.plusSeconds(1));
        List<String> sortedAssetIds = uploads.stream().map(RegisteredUpload::assetId).toList();

        assertThat(sortedAssetIds).isSorted();
        List<String> firstBatch = jdbc.query(
                "SELECT asset_id FROM media_asset WHERE status='UPLOADING' AND updated_at<? "
                        + "ORDER BY updated_at LIMIT 20",
                (rs, row) -> rs.getString("asset_id"),
                Timestamp.from(Instant.now().minusSeconds(300)));
        assertThat(firstBatch).hasSize(20)
                .containsExactlyInAnyOrderElementsOf(sortedAssetIds.subList(0, 20));

        RegisteredUpload valid = uploads.getLast();
        writeOriginal(valid.intent().objectKey(), validBytes);

        maintenance.reconcileUploads();

        assertThat(statusOf(valid.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(countJobs()).isZero();
        assertThat(countOutbox()).isZero();
        assertThat(statusesOf(sortedAssetIds)).containsOnly(MediaAsset.STATUS_UPLOADING);

        maintenance.reconcileUploads();

        assertThat(statusOf(valid.assetId())).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(countJobsFor(valid.assetId())).isEqualTo(1L);
        assertThat(countOutboxFor(valid.assetId())).isEqualTo(1L);
        assertThat(countJobs()).isEqualTo(1L);
        assertThat(countOutbox()).isEqualTo(1L);
        assertThat(statusesOf(sortedAssetIds.subList(0, 20)))
                .containsOnly(MediaAsset.STATUS_UPLOADING);
    }

    @Test
    void equalUpdatedAtTiesDrainAllValidOriginalsAcrossTwoBoundedPasses() throws Exception {
        byte[] validBytes = tinyJpeg();
        String validDigest = MediaMaintenance.digest(validBytes);
        List<RegisteredUpload> uploads = registerUploads("equal-timestamps", 21, validBytes.length, validDigest);
        Instant sameOldUpdatedAt = Instant.now().minus(Duration.ofMinutes(10));
        setUpdatedAt(uploads, sameOldUpdatedAt);
        List<String> assetIds = uploads.stream().map(RegisteredUpload::assetId).toList();
        assertThat(assetIds).isSorted();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT updated_at) FROM media_asset WHERE status='UPLOADING'",
                Long.class)).isEqualTo(1L);
        for (RegisteredUpload upload : uploads) {
            writeOriginal(upload.intent().objectKey(), validBytes);
        }

        List<String> firstBatch = jdbc.query(
                "SELECT asset_id FROM media_asset WHERE status='UPLOADING' AND updated_at<? "
                        + "ORDER BY updated_at LIMIT 20",
                (rs, row) -> rs.getString("asset_id"),
                Timestamp.from(Instant.now().minusSeconds(300)));
        assertThat(firstBatch).hasSize(20).doesNotHaveDuplicates();
        assertThat(assetIds).containsAll(firstBatch);

        maintenance.reconcileUploads();
        assertThat(countJobs()).isEqualTo(20L);
        assertThat(countOutbox()).isEqualTo(20L);

        maintenance.reconcileUploads();

        assertThat(statusesOf(assetIds)).containsOnly(MediaAsset.STATUS_READY);
        assertThat(countJobs()).isEqualTo(21L);
        assertThat(countOutbox()).isEqualTo(21L);
        for (String assetId : assetIds) {
            assertThat(countJobsFor(assetId)).isEqualTo(1L);
            assertThat(countOutboxFor(assetId)).isEqualTo(1L);
        }
    }

    @Test
    void equalUpdatedAtMissingObjectsDoNotHideTheLastAssetId() throws Exception {
        byte[] validBytes = tinyJpeg();
        List<RegisteredUpload> uploads = registerUploads("equal-missing", 21,
                validBytes.length, MediaMaintenance.digest(validBytes));
        setUpdatedAt(uploads, Instant.now().minus(Duration.ofMinutes(10)));
        RegisteredUpload valid = uploads.getLast();
        writeOriginal(valid.intent().objectKey(), validBytes);

        maintenance.reconcileUploads();
        assertThat(statusOf(valid.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        verify(objects, times(20)).readOriginal(eq("local"), anyString());

        maintenance.reconcileUploads();
        assertThat(statusOf(valid.assetId())).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(countJobsFor(valid.assetId())).isEqualTo(1L);
        assertThat(countOutboxFor(valid.assetId())).isEqualTo(1L);
        verify(objects, times(21)).readOriginal(eq("local"), anyString());
    }

    @Test
    void aFullFailedPageWrapsAndRetriesWhenAnOriginalBecomesAvailable() throws Exception {
        byte[] validBytes = tinyJpeg();
        List<RegisteredUpload> uploads = registerUploads("full-page-wrap", 20,
                validBytes.length, MediaMaintenance.digest(validBytes));
        setUpdatedAt(uploads, Instant.now().minus(Duration.ofMinutes(10)));
        RegisteredUpload availableLater = uploads.getFirst();

        maintenance.reconcileUploads();
        verify(objects, times(20)).readOriginal(eq("local"), anyString());
        assertThat(statusesOf(uploads.stream().map(RegisteredUpload::assetId).toList()))
                .containsOnly(MediaAsset.STATUS_UPLOADING);
        writeOriginal(availableLater.intent().objectKey(), validBytes);

        maintenance.reconcileUploads();
        verify(objects, times(40)).readOriginal(eq("local"), anyString());
        assertThat(statusOf(availableLater.assetId())).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(countJobsFor(availableLater.assetId())).isEqualTo(1L);
        assertThat(countOutboxFor(availableLater.assetId())).isEqualTo(1L);

        maintenance.reconcileUploads();
        assertThat(countJobsFor(availableLater.assetId())).isEqualTo(1L);
        assertThat(countOutboxFor(availableLater.assetId())).isEqualTo(1L);
    }

    @Test
    void anUploadThatAgesDuringAScanWaitsUntilTheNextRound() throws Exception {
        byte[] validBytes = tinyJpeg();
        List<RegisteredUpload> uploads = registerUploads("fixed-cutoff", 21,
                validBytes.length, MediaMaintenance.digest(validBytes));
        Instant old = Instant.now().minus(Duration.ofMinutes(10));
        setUpdatedAt(uploads.subList(0, 20), old);
        setUpdatedAt(List.of(uploads.getLast()), old.plusSeconds(1));
        RegisteredUpload olderValid = uploads.getLast();
        writeOriginal(olderValid.intent().objectKey(), validBytes);

        maintenance.reconcileUploads();
        verify(objects, times(20)).readOriginal(eq("local"), anyString());

        RegisteredUpload newlyEligible = registerUpload("fixed-cutoff-new", "00000000000000000000000000000001",
                validBytes.length, MediaMaintenance.digest(validBytes));
        Instant newlyEligibleAt = Instant.now().minusSeconds(300).plusMillis(300);
        setUpdatedAt(List.of(newlyEligible), newlyEligibleAt);
        writeOriginal(newlyEligible.intent().objectKey(), validBytes);
        Thread.sleep(700L);
        assertThat(newlyEligibleAt).isBefore(Instant.now().minusSeconds(300));

        maintenance.reconcileUploads();
        assertThat(statusOf(olderValid.assetId())).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(statusOf(newlyEligible.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        verify(objects, times(21)).readOriginal(eq("local"), anyString());

        maintenance.reconcileUploads();
        maintenance.reconcileUploads();
        assertThat(statusOf(newlyEligible.assetId())).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(countJobsFor(newlyEligible.assetId())).isEqualTo(1L);
        assertThat(countOutboxFor(newlyEligible.assetId())).isEqualTo(1L);
    }

    @Test
    void wrongOriginalDigestOrSizeStaysUploadingWithoutJobAndPreservesSourceBytes() throws Exception {
        byte[] sourceBytes = tinyJpeg();
        String actualDigest = MediaMaintenance.digest(sourceBytes);
        String wrongDigest = actualDigest.equals("a".repeat(64))
                ? "b".repeat(64)
                : "a".repeat(64);
        RegisteredUpload wrongDigestUpload = registerUpload("mismatch", "0000000000000000000000000000002a",
                sourceBytes.length, wrongDigest);
        RegisteredUpload wrongSizeUpload = registerUpload("mismatch", "0000000000000000000000000000002b",
                sourceBytes.length + 1L, actualDigest);
        Instant sameOldUpdatedAt = Instant.now().minus(Duration.ofMinutes(10));
        setUpdatedAt(List.of(wrongDigestUpload, wrongSizeUpload), sameOldUpdatedAt);
        writeOriginal(wrongDigestUpload.intent().objectKey(), sourceBytes);
        writeOriginal(wrongSizeUpload.intent().objectKey(), sourceBytes);

        maintenance.reconcileUploads();

        assertThat(statusOf(wrongDigestUpload.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(statusOf(wrongSizeUpload.assetId())).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(countJobs()).isZero();
        assertThat(countOutbox()).isZero();
        assertThat(readOriginal(wrongDigestUpload.intent().objectKey())).isEqualTo(sourceBytes);
        assertThat(readOriginal(wrongSizeUpload.intent().objectKey())).isEqualTo(sourceBytes);
    }

    private List<RegisteredUpload> registerUploads(String scope, int count, long byteSize, String sha256) {
        List<RegisteredUpload> uploads = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            uploads.add(registerUpload(scope, String.format("%032x", index + 1L), byteSize, sha256));
        }
        uploads.sort(Comparator.comparing(RegisteredUpload::assetId));
        return List.copyOf(uploads);
    }

    private RegisteredUpload registerUpload(String scope, String leaf, long byteSize, String sha256) {
        String objectKey = "threads/" + scope + "/2026/10/29/" + leaf + ".jpg";
        MediaUploadIntent intent = new MediaUploadIntent(
                "local",
                objectKey,
                "/uploads/" + objectKey,
                VALID_CONTENT_TYPE,
                byteSize,
                sha256);
        return new RegisteredUpload(registry.begin(OWNER_ID, intent), intent);
    }

    private void setUpdatedAt(List<RegisteredUpload> uploads, Instant updatedAt) {
        for (RegisteredUpload upload : uploads) {
            jdbc.update("UPDATE media_asset SET updated_at=? WHERE asset_id=?",
                    Timestamp.from(updatedAt), upload.assetId());
        }
    }

    private byte[] tinyJpeg() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpg", output)).isTrue();
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

    private record RegisteredUpload(String assetId, MediaUploadIntent intent) {
    }
}
