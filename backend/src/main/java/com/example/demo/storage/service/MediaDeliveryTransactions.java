package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaRequestEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Short database claims; broker network calls happen after these transactions commit. */
@Service
public class MediaDeliveryTransactions {
    private final JdbcTemplate jdbc;

    public MediaDeliveryTransactions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Optional<Delivery> claim() {
        Instant now = Instant.now();
        var rows = jdbc.query("""
                SELECT * FROM media_request_outbox
                WHERE ((status IN ('PENDING','FAILED') AND available_at<=?)
                    OR (status='IN_FLIGHT' AND lease_until<?))
                ORDER BY available_at,event_id LIMIT 1 FOR UPDATE
                """, (rs, row) -> new Delivery(rs.getString("event_id"), UUID.randomUUID().toString(),
                rs.getInt("attempts") + 1,
                new MediaRequestEnvelope(1, "MEDIA_PROCESSING_REQUESTED", rs.getString("event_id"),
                        rs.getString("asset_id"), rs.getString("job_id"), rs.getInt("job_version"),
                        rs.getString("preset"), rs.getString("processor_version"), rs.getTimestamp("occurred_at").toInstant())),
                Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) return Optional.empty();
        Delivery result = rows.getFirst();
        jdbc.update("UPDATE media_request_outbox SET status='IN_FLIGHT',attempts=?,lease_owner=?,lease_until=? WHERE event_id=?",
                result.attempts(), result.owner(), Timestamp.from(now.plusSeconds(45)), result.eventId());
        return Optional.of(result);
    }

    @Transactional
    public boolean published(Delivery delivery) {
        return jdbc.update("""
                UPDATE media_request_outbox SET status='PUBLISHED',lease_owner=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND status='IN_FLIGHT' AND lease_owner=? AND lease_until>?
                """, delivery.eventId(), delivery.owner(), Timestamp.from(Instant.now())) == 1;
    }

    @Transactional
    public void failed(Delivery delivery) {
        long backoff = Math.min(60, 1L << Math.min(6, delivery.attempts()));
        jdbc.update("""
                UPDATE media_request_outbox SET status='FAILED',lease_owner=NULL,lease_until=NULL,
                    available_at=?,last_error='PUBLISH_UNCONFIRMED'
                WHERE event_id=? AND status='IN_FLIGHT' AND lease_owner=? AND lease_until>?
                """, Timestamp.from(Instant.now().plusSeconds(backoff)), delivery.eventId(), delivery.owner(), Timestamp.from(Instant.now()));
    }

    @Transactional
    public void recover(String jobId) {
        var rows = jdbc.queryForList("SELECT status,lease_until,updated_at FROM media_processing_job WHERE job_id=? FOR UPDATE", jobId);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        Instant now = Instant.now();
        String status = (String) row.get("status");
        Instant lease = instant(row.get("lease_until"));
        Instant updated = instant(row.get("updated_at"));
        if (updated.isAfter(now.minusSeconds(120)) || !("PENDING".equals(status)
                || ("PROCESSING".equals(status) && lease != null && lease.isBefore(now)))) return;
        jdbc.update("""
                UPDATE media_request_outbox SET status='PENDING',available_at=?,lease_owner=NULL,lease_until=NULL
                WHERE job_id=? AND status IN ('PUBLISHED','FAILED')
                """, Timestamp.from(now), jobId);
        jdbc.update("UPDATE media_processing_job SET updated_at=? WHERE job_id=?", Timestamp.from(now), jobId);
    }

    public record Delivery(String eventId, String owner, int attempts, MediaRequestEnvelope envelope) {}

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant();
        throw new IllegalStateException("Unexpected database timestamp type");
    }
}
