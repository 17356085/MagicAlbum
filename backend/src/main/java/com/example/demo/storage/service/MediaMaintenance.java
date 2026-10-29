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

@Component
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaMaintenance {
    private static final Logger log = LoggerFactory.getLogger(MediaMaintenance.class);
    private final JdbcTemplate jdbc;
    private final MediaDeliveryTransactions deliveries;
    private final MediaObjectStore objects;
    private final MediaMaintenanceTransactions cleanup;
    private final Duration retention;

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
    public void recoverJobs() {
        Instant now = Instant.now();
        var jobIds = jdbc.queryForList("""
                SELECT job_id FROM media_processing_job
                WHERE (status='PENDING' AND updated_at<?)
                    OR (status='PROCESSING' AND lease_until<?)
                ORDER BY updated_at LIMIT 20
                """, String.class, Timestamp.from(now.minusSeconds(120)), Timestamp.from(now));
        int failures = 0;
        String firstFailedJob = null;
        String firstFailureType = null;
        for (String jobId : jobIds) {
            try {
                deliveries.recover(jobId);
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                failures++;
                if (firstFailedJob == null) {
                    firstFailedJob = jobId;
                    firstFailureType = failure.getClass().getSimpleName();
                }
            }
        }
        if (failures > 0) {
            log.warn("media_job_recovery_deferred count={} firstJobId={} errorType={} reason=RECOVERY_UNCONFIRMED",
                    failures, firstFailedJob, firstFailureType);
        }
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
}
