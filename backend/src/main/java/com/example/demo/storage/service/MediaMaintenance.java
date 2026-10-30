package com.example.demo.storage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.example.demo.storage.service.MediaDeliveryTransactions.PENDING_RECOVERY_GRACE_SECONDS;

@Component
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaMaintenance {
    private static final int RECOVERY_BATCH_SIZE = 20;
    private static final Logger log = LoggerFactory.getLogger(MediaMaintenance.class);
    private final JdbcTemplate jdbc;
    private final MediaDeliveryTransactions deliveries;
    private final MediaObjectStore objects;
    private final MediaMaintenanceTransactions cleanup;
    private final Duration retention;
    // The cutoff keeps a round finite; each cursor uses the time at which a job
    // first became eligible. Progress is per instance and resets on restart.
    private Instant recoveryCutoff;
    private RecoveryCursor recoveryCursor;

    public MediaMaintenance(JdbcTemplate jdbc, MediaDeliveryTransactions deliveries,
            MediaObjectStore objects, MediaMaintenanceTransactions cleanup,
            @Value("${app.media.orphan-retention:24h}") Duration retention,
            @Value("${app.media.registration-enabled:false}") boolean registrationEnabled) {
        if (!registrationEnabled) throw new IllegalStateException("Media processing requires upload registration");
        if (retention.compareTo(Duration.ofMinutes(20)) < 0 || retention.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("Media orphan retention must be between 20 minutes and 90 days");
        }
        this.jdbc = jdbc;
        this.deliveries = deliveries;
        this.objects = objects;
        this.cleanup = cleanup;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.media.recovery-delay-ms:15000}", scheduler = "mediaPublishScheduler")
    public synchronized void recoverJobs() {
        beginRecoveryRoundIfNeeded();
        var jobs = loadRecoveryBatch();
        if (jobs.isEmpty()) {
            boolean reachedEndOfExistingRound = recoveryCursor != null;
            resetRecoveryRound();
            if (!reachedEndOfExistingRound) return;
            beginRecoveryRoundIfNeeded();
            jobs = loadRecoveryBatch();
            if (jobs.isEmpty()) {
                resetRecoveryRound();
                return;
            }
        }
        int failures = 0;
        String firstFailedJob = null;
        String firstFailureType = null;
        for (RecoveryJob job : jobs) {
            try {
                deliveries.recover(job.jobId());
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                failures++;
                if (firstFailedJob == null) {
                    firstFailedJob = job.jobId();
                    firstFailureType = failure.getClass().getSimpleName();
                }
            }
        }
        if (failures > 0) {
            log.warn("media_job_recovery_deferred count={} firstJobId={} errorType={} reason=RECOVERY_UNCONFIRMED",
                    failures, firstFailedJob, firstFailureType);
        }
        if (jobs.size() < RECOVERY_BATCH_SIZE) {
            resetRecoveryRound();
        } else {
            RecoveryJob last = jobs.getLast();
            recoveryCursor = new RecoveryCursor(last.eligibleAt(), last.jobId());
        }
    }

    private void beginRecoveryRoundIfNeeded() {
        if (recoveryCutoff == null) {
            recoveryCutoff = Instant.now();
            recoveryCursor = null;
        }
    }

    private List<RecoveryJob> loadRecoveryBatch() {
        String pendingSeek = seekAfter("updated_at");
        String processingSeek = seekAfter("lease_until");
        // Each branch can use the corresponding status and due-time index. At most
        // 40 candidates are merged in the database to retain its ID collation.
        String sql = """
                SELECT job_id,eligible_at FROM (
                    (SELECT job_id,TIMESTAMPADD(SECOND,%d,updated_at) AS eligible_at FROM media_processing_job
                     WHERE status='PENDING' AND updated_at<? %s
                     ORDER BY updated_at,job_id LIMIT %d)
                    UNION ALL
                    (SELECT job_id,lease_until AS eligible_at FROM media_processing_job
                     WHERE status='PROCESSING' AND lease_until<? %s
                     ORDER BY lease_until,job_id LIMIT %d)
                ) recovery_candidates ORDER BY eligible_at,job_id LIMIT %d
                """.formatted(PENDING_RECOVERY_GRACE_SECONDS, pendingSeek, RECOVERY_BATCH_SIZE,
                processingSeek, RECOVERY_BATCH_SIZE, RECOVERY_BATCH_SIZE);
        List<Object> parameters = new ArrayList<>();
        addRecoveryParameters(parameters, recoveryCutoff.minusSeconds(PENDING_RECOVERY_GRACE_SECONDS), true);
        addRecoveryParameters(parameters, recoveryCutoff, false);
        return jdbc.query(sql, (rs, row) -> new RecoveryJob(rs.getString("job_id"),
                rs.getTimestamp("eligible_at").toInstant()), parameters.toArray());
    }

    private String seekAfter(String timeColumn) {
        return recoveryCursor == null ? "" : " AND (" + timeColumn + ">? OR (" + timeColumn + "=? AND job_id>?))";
    }

    private void addRecoveryParameters(List<Object> parameters, Instant dueBefore, boolean pending) {
        parameters.add(Timestamp.from(dueBefore));
        if (recoveryCursor != null) {
            Instant cursorTime = pending ? recoveryCursor.eligibleAt().minusSeconds(PENDING_RECOVERY_GRACE_SECONDS)
                    : recoveryCursor.eligibleAt();
            parameters.add(Timestamp.from(cursorTime));
            parameters.add(Timestamp.from(cursorTime));
            parameters.add(recoveryCursor.jobId());
        }
    }

    private void resetRecoveryRound() {
        recoveryCutoff = null;
        recoveryCursor = null;
    }

    @Scheduled(fixedDelayString = "${app.media.cleanup-delay-ms:60000}", scheduler = "mediaMaintenanceScheduler")
    public void cleanDerivedObjects() {
        for (String provider : new String[]{"local", "s3"}) {
            boolean configured = Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM media_asset WHERE provider=?", Boolean.class, provider));
            if (!configured) continue;
            try {
                for (var object : objects.scanDerived(provider, Instant.now().minus(retention))) {
                    cleanup.deleteUnreferenced(provider, object.objectKey());
                }
            } catch (RuntimeException failure) {
                log.warn("media_cleanup_deferred reason=OBJECT_UNCONFIRMED");
            }
        }
    }

    private record RecoveryJob(String jobId, Instant eligibleAt) {}
    private record RecoveryCursor(Instant eligibleAt, String jobId) {}
}
