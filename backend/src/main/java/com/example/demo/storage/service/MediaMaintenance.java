package com.example.demo.storage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaMaintenance {
    private static final Logger log = LoggerFactory.getLogger(MediaMaintenance.class);
    private final JdbcTemplate jdbc;
    private final MediaDeliveryTransactions deliveries;
    private final MediaUploadRegistrationService registry;
    private final MediaObjectStore objects;
    private final MediaMaintenanceTransactions cleanup;
    private final Duration retention;

    public MediaMaintenance(JdbcTemplate jdbc, MediaDeliveryTransactions deliveries,
            MediaUploadRegistrationService registry, MediaObjectStore objects, MediaMaintenanceTransactions cleanup,
            @Value("${app.media.orphan-retention:24h}") Duration retention,
            @Value("${app.media.registration-enabled:false}") boolean registrationEnabled) {
        if (!registrationEnabled) throw new IllegalStateException("Media processing requires upload registration");
        if (retention.compareTo(Duration.ofMinutes(20)) < 0 || retention.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("Media orphan retention must be between 20 minutes and 90 days");
        }
        this.jdbc = jdbc;
        this.deliveries = deliveries;
        this.registry = registry;
        this.objects = objects;
        this.cleanup = cleanup;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.media.recovery-delay-ms:15000}", scheduler = "mediaPublishScheduler")
    public void recoverJobs() {
        jdbc.queryForList("""
                SELECT job_id FROM media_processing_job
                WHERE status IN ('PENDING','PROCESSING') AND updated_at<? ORDER BY updated_at LIMIT 20
                """, String.class, Timestamp.from(Instant.now().minusSeconds(120))).forEach(deliveries::recover);
    }

    @Scheduled(fixedDelayString = "${app.media.reconcile-delay-ms:60000}", scheduler = "mediaMaintenanceScheduler")
    public void reconcileUploads() {
        var rows = jdbc.query("""
                SELECT asset_id,provider,object_key,byte_size,sha256 FROM media_asset
                WHERE status='UPLOADING' AND updated_at<? ORDER BY updated_at LIMIT 20
                """, (rs, row) -> new Upload(rs.getString("asset_id"), rs.getString("provider"),
                rs.getString("object_key"), rs.getLong("byte_size"), rs.getString("sha256")),
                Timestamp.from(Instant.now().minusSeconds(300)));
        for (Upload upload : rows) {
            try {
                byte[] bytes = objects.readOriginal(upload.provider(), upload.key());
                if (bytes.length == upload.size() && digest(bytes).equals(upload.sha256())) registry.ready(upload.assetId());
            } catch (RuntimeException failure) {
                log.warn("media_reconcile_deferred assetId={} reason=OBJECT_UNCONFIRMED", upload.assetId());
            }
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

    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Upload(String assetId, String provider, String key, long size, String sha256) {}
}
