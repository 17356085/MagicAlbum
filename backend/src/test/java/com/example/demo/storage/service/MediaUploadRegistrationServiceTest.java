package com.example.demo.storage.service;

import com.example.demo.Demo1Application;
import com.example.demo.storage.dto.MediaAssetStatus;
import com.example.demo.storage.dto.MediaUploadIntent;
import com.example.demo.storage.entity.MediaAsset;
import com.example.demo.storage.entity.MediaProcessingJob;
import com.example.demo.storage.entity.MediaRequestOutbox;
import com.example.demo.storage.repo.MediaAssetRepository;
import com.example.demo.storage.repo.MediaProcessingJobRepository;
import com.example.demo.storage.repo.MediaRequestOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest(classes = Demo1Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:media-registry;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MediaUploadRegistrationServiceTest {

    private static final String SHA256 = "a".repeat(64);

    @Autowired
    private MediaUploadRegistrationService service;

    @Autowired
    private MediaAssetRepository assetRepository;

    @Autowired
    private MediaProcessingJobRepository jobRepository;

    @Autowired
    private MediaRequestOutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearRegistry() {
        inNewTransaction(() -> {
            outboxRepository.deleteAll();
            jobRepository.deleteAll();
            assetRepository.deleteAll();
            return null;
        });
    }

    @Test
    void begin_commits_uploading_metadata_and_findOwned_hides_internal_fields() {
        String assetId = service.begin(7L, intent("image/jpeg"));

        MediaAsset stored = readAsset(assetId);
        assertThat(stored.getAssetId()).isEqualTo(assetId);
        assertThat(stored.getOwnerId()).isEqualTo(7L);
        assertThat(stored.getProvider()).isEqualTo("local");
        assertThat(stored.getObjectKey()).endsWith(".jpg");
        assertThat(stored.getStatus()).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(stored.getProcessingStatus()).isEqualTo(MediaAsset.PROCESSING_NOT_REQUESTED);

        MediaAssetStatus publicStatus = service.findOwned(7L, assetId);
        assertThat(publicStatus).isEqualTo(new MediaAssetStatus(
                assetId, stored.getUrl(),
                MediaAsset.STATUS_UPLOADING, null, MediaAsset.PROCESSING_NOT_REQUESTED));
    }

    @Test
    void ready_is_idempotent_and_creates_one_job_and_one_outbox_request() {
        String assetId = service.begin(7L, intent("image/jpeg"));

        MediaAssetStatus first = service.ready(assetId);
        MediaAssetStatus repeated = service.ready(assetId);

        assertThat(first).isEqualTo(repeated);
        assertThat(first.assetId()).isEqualTo(assetId);
        assertThat(first.status()).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(first.processingStatus()).isEqualTo(MediaAsset.PROCESSING_PENDING);
        assertThat(first.jobId()).isNotBlank();

        MediaProcessingJob job = readJob(first.jobId());
        assertThat(job.getAssetId()).isEqualTo(assetId);
        assertThat(job.getJobVersion()).isEqualTo(1);
        assertThat(job.getPreset()).isEqualTo(MediaUploadRegistrationService.PRESET_PREVIEW);
        assertThat(job.getProcessorVersion()).isEqualTo(MediaUploadRegistrationService.PROCESSOR_VERSION);
        assertThat(job.getStatus()).isEqualTo(MediaProcessingJob.STATUS_PENDING);
        assertThat(job.getAttempts()).isZero();

        MediaRequestOutbox outbox = readOutbox(first.jobId());
        assertThat(outbox.getAssetId()).isEqualTo(assetId);
        assertThat(outbox.getJobVersion()).isEqualTo(1);
        assertThat(outbox.getPreset()).isEqualTo(MediaUploadRegistrationService.PRESET_PREVIEW);
        assertThat(outbox.getProcessorVersion()).isEqualTo(MediaUploadRegistrationService.PROCESSOR_VERSION);
        assertThat(outbox.getStatus()).isEqualTo(MediaRequestOutbox.STATUS_PENDING);
        assertThat(countJobs(assetId)).isEqualTo(1);
        assertThat(countOutbox(first.jobId())).isEqualTo(1);
    }

    @Test
    void concurrent_ready_calls_share_one_job_and_one_outbox_request() throws Exception {
        String assetId = service.begin(7L, intent("image/png"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<MediaAssetStatus> first = workers.submit(() -> readyAfterBarrier(assetId, start));
            Future<MediaAssetStatus> second = workers.submit(() -> readyAfterBarrier(assetId, start));

            MediaAssetStatus firstResult = first.get(10, TimeUnit.SECONDS);
            MediaAssetStatus secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult).isEqualTo(secondResult);
            assertThat(firstResult.jobId()).isNotBlank();
            assertThat(countJobs(assetId)).isEqualTo(1);
            assertThat(countOutbox(firstResult.jobId())).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void gif_and_webp_become_ready_without_processing_jobs() {
        String gifAssetId = service.begin(7L, intent("image/gif"));
        String webpAssetId = service.begin(7L, intent("image/webp"));

        MediaAssetStatus gif = service.ready(gifAssetId);
        MediaAssetStatus webp = service.ready(webpAssetId);

        assertThat(gif.status()).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(gif.processingStatus()).isEqualTo(MediaAsset.PROCESSING_UNSUPPORTED);
        assertThat(gif.jobId()).isNull();
        assertThat(webp.status()).isEqualTo(MediaAsset.STATUS_READY);
        assertThat(webp.processingStatus()).isEqualTo(MediaAsset.PROCESSING_UNSUPPORTED);
        assertThat(webp.jobId()).isNull();
        assertThat(jobRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void failed_state_is_terminal_and_ready_does_not_overwrite_it() {
        String failedAssetId = service.begin(7L, intent("image/png"));
        MediaAssetStatus failed = service.failed(failedAssetId);

        assertThat(failed.status()).isEqualTo(MediaAsset.STATUS_UPLOAD_FAILED);
        assertThat(failed.processingStatus()).isEqualTo(MediaAsset.PROCESSING_NOT_REQUESTED);
        assertThat(service.ready(failedAssetId)).isEqualTo(failed);

        String readyAssetId = service.begin(7L, intent("image/png"));
        MediaAssetStatus ready = service.ready(readyAssetId);
        assertThat(service.failed(readyAssetId)).isEqualTo(ready);
        assertThat(service.findOwned(7L, readyAssetId)).isEqualTo(ready);
        assertThat(countJobs(readyAssetId)).isEqualTo(1);
        assertThat(countOutbox(ready.jobId())).isEqualTo(1);
    }

    @Test
    void other_owner_receives_not_found_and_invalid_input_writes_nothing() {
        String assetId = service.begin(7L, intent("image/jpeg"));

        ResponseStatusException otherOwner = catchThrowableOfType(
                () -> service.findOwned(8L, assetId), ResponseStatusException.class);
        ResponseStatusException unknownAsset = catchThrowableOfType(
                () -> service.findOwned(7L, "00000000-0000-0000-0000-000000000000"), ResponseStatusException.class);
        assertThat(otherOwner).isNotNull();
        assertThat(unknownAsset).isNotNull();
        assertThat(otherOwner.getStatusCode()).isEqualTo(unknownAsset.getStatusCode());
        assertThat(otherOwner.getReason()).isEqualTo(unknownAsset.getReason());
        assertThat(otherOwner.getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> service.begin(0L, intent("image/jpeg")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.begin(7L, new MediaUploadIntent(
                "local", "key", "/uploads/key", "image/jpeg", 1L, "A".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countAssets()).isEqualTo(1);
    }

    @Test
    void outbox_constraint_failure_rolls_back_ready_and_job_writes() {
        String assetId = service.begin(7L, intent("image/jpeg"));
        jdbcTemplate.execute("ALTER TABLE media_request_outbox "
                + "ADD CONSTRAINT test_reject_media_request CHECK (1 = 0)");
        try {
            assertThatThrownBy(() -> service.ready(assetId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE media_request_outbox "
                    + "DROP CONSTRAINT test_reject_media_request");
        }

        MediaAsset afterRollback = readAsset(assetId);
        assertThat(afterRollback.getStatus()).isEqualTo(MediaAsset.STATUS_UPLOADING);
        assertThat(afterRollback.getProcessingStatus()).isEqualTo(MediaAsset.PROCESSING_NOT_REQUESTED);
        assertThat(countJobs(assetId)).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    private MediaUploadIntent intent(String contentType) {
        String suffix = switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "bin";
        };
        String key = "threads/2026/10/19/" + UUID.randomUUID().toString().replace("-", "") + "." + suffix;
        return new MediaUploadIntent(
                "local",
                key,
                "/uploads/" + key,
                contentType,
                1024L,
                SHA256);
    }

    private MediaAssetStatus readyAfterBarrier(String assetId, CyclicBarrier start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return service.ready(assetId);
    }

    private MediaAsset readAsset(String assetId) {
        return inNewTransaction(() -> assetRepository.findById(assetId).orElseThrow());
    }

    private MediaProcessingJob readJob(String jobId) {
        return inNewTransaction(() -> jobRepository.findById(jobId).orElseThrow());
    }

    private MediaRequestOutbox readOutbox(String jobId) {
        return inNewTransaction(() -> outboxRepository.findByJobIdAndJobVersion(jobId, 1).orElseThrow());
    }

    private long countAssets() {
        return inNewTransaction(assetRepository::count);
    }

    private long countJobs(String assetId) {
        return inNewTransaction(() -> jobRepository.countByAssetId(assetId));
    }

    private long countOutbox(String jobId) {
        return inNewTransaction(() -> outboxRepository.countByJobIdAndJobVersion(jobId, 1));
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }
}
