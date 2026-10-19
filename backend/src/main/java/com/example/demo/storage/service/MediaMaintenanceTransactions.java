package com.example.demo.storage.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MediaMaintenanceTransactions {
    private final JdbcTemplate jdbc;
    private final MediaObjectStore objects;

    public MediaMaintenanceTransactions(JdbcTemplate jdbc, MediaObjectStore objects) {
        this.jdbc = jdbc;
        this.objects = objects;
    }

    /**
     * A rare bounded DELETE holds the same job lock as result reservation and
     * acceptance. This prevents deleting a newly reserved content-addressed key.
     * Unknown database state is retained rather than guessed to be disposable.
     */
    @Transactional
    public boolean deleteUnreferenced(String provider, String key) {
        String canonical = key.replaceFirst("\\.tmp-[0-9a-f-]{36}$", "");
        MediaObjectStore.requireDerivedKey(canonical);
        String[] segments = canonical.split("/");
        int version;
        try { version = Integer.parseInt(segments[2].substring(1)); }
        catch (NumberFormatException invalid) { return false; }
        var jobs = jdbc.query("""
                SELECT j.output_key,j.candidate_key,j.status,j.lease_until,a.provider
                FROM media_processing_job j JOIN media_asset a ON j.asset_id=a.asset_id
                WHERE j.asset_id=? AND j.job_version=? AND j.preset=? FOR UPDATE
                """, (rs, row) -> new Protection(rs.getString("output_key"), rs.getString("candidate_key"),
                rs.getString("status"), rs.getTimestamp("lease_until") == null ? null
                        : rs.getTimestamp("lease_until").toInstant(), rs.getString("provider")), segments[1], version, segments[3]);
        if (jobs.isEmpty()) return false;
        Protection protection = jobs.getFirst();
        if (!provider.equals(protection.provider()) || key.equals(protection.outputKey())) return false;
        if (canonical.equals(protection.candidateKey()) && "PROCESSING".equals(protection.status())
                && protection.leaseUntil() != null && protection.leaseUntil().isAfter(Instant.now())) return false;
        objects.deleteDerived(provider, key);
        return true;
    }

    private record Protection(String outputKey, String candidateKey, String status, Instant leaseUntil, String provider) {}
}
