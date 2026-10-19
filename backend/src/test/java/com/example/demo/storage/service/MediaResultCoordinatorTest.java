package com.example.demo.storage.service;

import com.example.demo.Demo1Application;
import com.example.demo.storage.dto.MediaClaimResponse;
import com.example.demo.storage.dto.MediaExecutionResponse;
import com.example.demo.storage.dto.MediaRequestEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = Demo1Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:media-result;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.media.processing-lease-duration=10s"
})
class MediaResultCoordinatorTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String INPUT_SHA = "b".repeat(64);

    @Autowired
    private MediaJobService jobService;

    @Autowired
    private MediaResultCoordinator coordinator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private MediaObjectStore objectStore;

    @BeforeEach
    void clear() {
        jdbc.update("delete from media_request_outbox");
        jdbc.update("delete from media_processing_job");
        jdbc.update("delete from media_asset");
    }

    @Test
    void validatesOutputAndAcceptsWithAtomicStatusUpdate() throws Exception {
        Fixture fixture = fixture();
        MediaClaimResponse claim = jobService.claim(fixture.jobId(), fixture.envelope());
        byte[] output = png(4, 3);
        String outputSha = sha256(output);
        MediaObjectStore.StoredObject stored = new MediaObjectStore.StoredObject(
                "local", "media/" + fixture.assetId() + "/v1/preview/p1/" + outputSha + ".png",
                "/uploads/derived/" + outputSha + ".png");
        when(objectStore.planDerived("local", fixture.assetId(), 1, "preview", "1", outputSha, "image/png"))
                .thenReturn(stored);
        doNothing().when(objectStore).write(stored, output);

        MediaExecutionResponse response = coordinator.submit(
                fixture.jobId(), output, 1, INPUT_SHA, outputSha, "1", 4, 3, claim.leaseToken());
        assertThat(response.status()).isEqualTo(MediaJobService.STATUS_SUCCEEDED);
        assertThat(jdbc.queryForObject("select status from media_processing_job where job_id=?",
                String.class, fixture.jobId())).isEqualTo(MediaJobService.STATUS_SUCCEEDED);
        assertThat(jdbc.queryForObject("select processing_status from media_asset where asset_id=?",
                String.class, fixture.assetId())).isEqualTo(MediaJobService.PROCESSING_SUCCEEDED);
        verify(objectStore).write(stored, output);
    }

    @Test
    void rejectsInputDigestAndDoesNotPlanOrWriteObject() throws Exception {
        Fixture fixture = fixture();
        MediaClaimResponse claim = jobService.claim(fixture.jobId(), fixture.envelope());
        byte[] output = png(4, 3);
        String outputSha = sha256(output);

        assertThatThrownBy(() -> coordinator.submit(
                fixture.jobId(), output, 1, "c".repeat(64), outputSha, "1", 4, 3, claim.leaseToken()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
        verify(objectStore, never()).planDerived(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
    }

    @Test
    void identicalCompletedLeaseRetryIsIdempotentButConflictingResultCannotOverwrite() throws Exception {
        Fixture fixture = fixture();
        MediaClaimResponse claim = jobService.claim(fixture.jobId(), fixture.envelope());
        byte[] output = png(4, 3);
        String outputSha = sha256(output);
        MediaObjectStore.StoredObject stored = new MediaObjectStore.StoredObject(
                "local", "media/" + fixture.assetId() + "/v1/preview/p1/" + outputSha + ".png",
                "/uploads/derived/" + outputSha + ".png");
        when(objectStore.planDerived(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(stored);
        doNothing().when(objectStore).write(stored, output);

        coordinator.submit(fixture.jobId(), output, 1, INPUT_SHA, outputSha, "1", 4, 3, claim.leaseToken());
        jdbc.update("update media_processing_job set lease_until=? where job_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), fixture.jobId());
        assertThat(coordinator.submit(
                fixture.jobId(), output, 1, INPUT_SHA, outputSha, "1", 4, 3, claim.leaseToken()).status())
                .isEqualTo(MediaJobService.STATUS_SUCCEEDED);
        verify(objectStore).write(stored, output);

        byte[] different = png(3, 3);
        String differentSha = sha256(different);
        assertThatThrownBy(() -> coordinator.submit(
                fixture.jobId(), different, 1, INPUT_SHA, differentSha, "1", 3, 3, claim.leaseToken()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
        verify(objectStore, never()).write(ArgumentMatchers.any(), ArgumentMatchers.eq(different));
    }

    @Test
    void lateCallbackCannotAcceptAfterReclaimOrOwnerCancel() throws Exception {
        Fixture fixture = fixture();
        MediaClaimResponse first = jobService.claim(fixture.jobId(), fixture.envelope());
        jdbc.update("update media_processing_job set lease_until=? where job_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), fixture.jobId());
        MediaClaimResponse second = jobService.claim(fixture.jobId(), fixture.envelope());

        byte[] output = png(4, 3);
        String outputSha = sha256(output);
        MediaObjectStore.StoredObject stored = new MediaObjectStore.StoredObject(
                "local", "media/" + fixture.assetId() + "/v1/preview/p1/" + outputSha + ".png",
                "/uploads/derived/" + outputSha + ".png");
        when(objectStore.planDerived(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(stored);

        assertThatThrownBy(() -> coordinator.submit(
                fixture.jobId(), output, 1, INPUT_SHA, outputSha, "1", 4, 3, first.leaseToken()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
        verify(objectStore, never()).write(ArgumentMatchers.any(), ArgumentMatchers.eq(output));

        assertThat(jobService.cancelOwned(fixture.assetId(), 7L).status())
                .isEqualTo(MediaJobService.STATUS_CANCELLED);
        assertThatThrownBy(() -> coordinator.submit(
                fixture.jobId(), output, 1, INPUT_SHA, outputSha, "1", 4, 3, second.leaseToken()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
        verify(objectStore, never()).write(ArgumentMatchers.any(), ArgumentMatchers.eq(output));
    }

    private Fixture fixture() throws Exception {
        String assetId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        tx(() -> {
            jdbc.update("""
                    insert into media_asset(asset_id, owner_id, provider, object_key, url, content_type,
                        byte_size, sha256, status, processing_status, created_at, updated_at)
                    values (?, 7, 'local', 'input.png', '/uploads/input.png', 'image/png',
                        4, ?, 'READY', 'PENDING', ?, ?)
                    """, assetId, INPUT_SHA, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            jdbc.update("""
                    insert into media_processing_job(job_id, asset_id, job_version, preset,
                        processor_version, status, attempts, created_at, updated_at)
                    values (?, ?, 1, 'preview', '1', 'PENDING', 0, ?, ?)
                    """, jobId, assetId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            jdbc.update("""
                    insert into media_request_outbox(event_id, asset_id, job_id, job_version,
                        preset, processor_version, occurred_at, status, attempts, available_at)
                    values (?, ?, ?, 1, 'preview', '1', ?, 'PENDING', 0, ?)
                    """, eventId, assetId, jobId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            return null;
        });
        return new Fixture(assetId, jobId, new MediaRequestEnvelope(
                1, "MEDIA_PROCESSING_REQUESTED", eventId, assetId, jobId, 1,
                "preview", "1", now));
    }

    private byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> work.get());
    }

    private record Fixture(String assetId, String jobId, MediaRequestEnvelope envelope) {
    }
}
