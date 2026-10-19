package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaClaimResponse;
import com.example.demo.storage.dto.MediaExecutionResponse;
import com.example.demo.storage.dto.MediaFailureRequest;
import com.example.demo.storage.dto.MediaInputAuthorization;
import com.example.demo.storage.dto.MediaRequestEnvelope;
import com.example.demo.storage.dto.MediaRenewRequest;
import com.example.demo.storage.dto.MediaRenewResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns the short database transactions around a media processing lease.
 *
 * <p>Object storage is deliberately absent from this service. A worker can
 * claim, renew, read and report a job without holding a database transaction
 * while bytes are being transferred.</p>
 */
@Service
public class MediaJobService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** Wire-level response status for a successful lease claim. */
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String PROCESSING_PROCESSING = "PROCESSING";
    public static final String PROCESSING_SUCCEEDED = "SUCCEEDED";
    public static final String PROCESSING_FAILED = "FAILED";
    public static final String PROCESSING_CANCELLED = "CANCELLED";
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private static final Duration MIN_LEASE = Duration.ofSeconds(10);
    private static final Duration MAX_LEASE = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int maxAttempts;

    @Autowired
    public MediaJobService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.media.processing-lease-duration:120s}") Duration leaseDuration,
            @Value("${app.media.processing-max-attempts:5}") int maxAttempts
    ) {
        this(jdbcTemplate, Clock.systemUTC(), leaseDuration, maxAttempts);
    }

    /** Injectable clock/lease constructor used by focused execution tests. */
    public MediaJobService(JdbcTemplate jdbcTemplate, Clock clock, Duration leaseDuration, int maxAttempts) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requireLeaseDuration(leaseDuration);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Claims a message after checking the path id, outbox event, job and
     * asset metadata while holding the job row lock.
     */
    @Transactional
    public MediaClaimResponse claim(String pathJobId, MediaRequestEnvelope envelope) {
        if (envelope == null || !envelope.valid()) {
            throw badRequest("媒体任务消息不合法");
        }
        if (pathJobId == null || !pathJobId.equals(envelope.jobId())) {
            throw notFound();
        }

        JobRow job = findJobForUpdate(pathJobId);
        if (job == null || !matches(job, envelope)) {
            throw notFound();
        }
        OutboxRow outbox = findOutbox(envelope.eventId());
        if (outbox == null || !matches(outbox, envelope)) {
            throw notFound();
        }
        AssetRow asset = findAsset(envelope.assetId());
        if (asset == null || !envelope.assetId().equals(asset.assetId())) {
            throw notFound();
        }

        if (isTerminal(job.status())) {
            return terminal(job.status());
        }

        Instant now = now();
        if (STATUS_PROCESSING.equals(job.status())
                && job.leaseUntil() != null
                && job.leaseUntil().isAfter(now)) {
            throw busy();
        }
        if (!STATUS_PENDING.equals(job.status()) && !STATUS_PROCESSING.equals(job.status())) {
            throw conflict("媒体任务当前不可领取");
        }
        if (job.attempts() >= maxAttempts) {
            markTimedOut(job, now);
            return terminal(STATUS_FAILED);
        }

        String leaseToken = freshLeaseToken();
        Instant leaseUntil = now.plus(leaseDuration);
        int attempts = job.attempts() + 1;
        jdbcTemplate.update("""
                update media_processing_job
                   set status = ?, attempts = ?, lease_token_hash = ?, lease_until = ?,
                       candidate_key = null, candidate_url = null, candidate_sha256 = null,
                       candidate_type = null, candidate_size = null,
                       candidate_width = null, candidate_height = null,
                       error_code = null, updated_at = ?
                 where job_id = ?
                """,
                STATUS_PROCESSING,
                attempts,
                hashLeaseToken(leaseToken),
                timestamp(leaseUntil),
                timestamp(now),
                job.jobId());
        updateAssetProcessingStatus(asset.assetId(), PROCESSING_PROCESSING, now);

        return new MediaClaimResponse(
                STATUS_CLAIMED,
                asset.assetId(),
                job.jobVersion(),
                job.preset(),
                job.processorVersion(),
                leaseToken,
                leaseUntil,
                asset.sha256(),
                asset.contentType(),
                asset.byteSize());
    }

    /** Renews only an unexpired lease held by the same worker token. */
    @Transactional
    public MediaRenewResponse renew(String jobId, MediaRenewRequest request) {
        if (request == null || request.jobVersion() < 1 || blank(request.leaseToken())) {
            throw badRequest("租约请求不合法");
        }
        JobRow job = findJobForUpdate(jobId);
        if (job == null) {
            throw notFound();
        }
        requireVersion(job, request.jobVersion());
        Instant now = now();
        requireCurrentLease(job, request.leaseToken(), now);

        Instant leaseUntil = now.plus(leaseDuration);
        jdbcTemplate.update("update media_processing_job set lease_until = ?, updated_at = ? where job_id = ?",
                timestamp(leaseUntil), timestamp(now), job.jobId());
        return new MediaRenewResponse(leaseUntil);
    }

    /**
     * Authorizes the worker to read one exact original object. The returned
     * capability is consumed outside this transaction by the controller.
     */
    @Transactional
    public MediaInputAuthorization authorizeInput(String jobId, int jobVersion, String leaseToken) {
        if (jobVersion < 1 || blank(leaseToken)) {
            throw badRequest("输入读取请求不合法");
        }
        JobRow job = findJobForUpdate(jobId);
        if (job == null) {
            throw notFound();
        }
        requireVersion(job, jobVersion);
        requireCurrentLease(job, leaseToken, now());
        AssetRow asset = findAsset(job.assetId());
        if (asset == null) {
            throw notFound();
        }
        return new MediaInputAuthorization(
                asset.provider(), asset.objectKey(), asset.sha256(), asset.contentType(), asset.byteSize());
    }

    /** Records one fixed permanent worker failure under the current lease. */
    @Transactional
    public MediaExecutionResponse fail(String jobId, MediaFailureRequest request) {
        if (request == null || request.jobVersion() < 1
                || blank(request.leaseToken()) || !isFailureCode(request.errorCode())) {
            throw badRequest("媒体失败代码不合法");
        }
        JobRow job = findJobForUpdate(jobId);
        if (job == null) {
            throw notFound();
        }
        requireVersion(job, request.jobVersion());
        String providedHash = hashLeaseToken(request.leaseToken());
        if (STATUS_FAILED.equals(job.status())) {
            if (sameLease(job.leaseTokenHash(), providedHash)
                    && request.errorCode().equals(job.errorCode())) {
                return new MediaExecutionResponse(STATUS_FAILED);
            }
            throw conflict("媒体任务结果已冲突");
        }
        if (isTerminal(job.status())) {
            throw conflict("媒体任务已结束");
        }
        requireCurrentLeaseHash(job, providedHash, now());

        Instant now = now();
        jdbcTemplate.update("""
                update media_processing_job
                   set status = ?, error_code = ?,
                       candidate_key = null, candidate_url = null, candidate_sha256 = null,
                       candidate_type = null, candidate_size = null,
                       candidate_width = null, candidate_height = null,
                       updated_at = ?
                 where job_id = ?
                """,
                STATUS_FAILED,
                request.errorCode(),
                timestamp(now),
                job.jobId());
        updateAssetProcessingStatus(job.assetId(), PROCESSING_FAILED, now);
        return new MediaExecutionResponse(STATUS_FAILED);
    }

    /**
     * Cancels an owner-visible processing job. Ownership is checked before the
     * job lock; the job lock then serializes cancellation against claim/result.
     */
    @Transactional
    public MediaExecutionResponse cancelOwned(String assetId, Long ownerId) {
        if (blank(assetId) || ownerId == null || ownerId <= 0) {
            throw notFound();
        }
        AssetRow asset = findAsset(assetId);
        if (asset == null || !ownerId.equals(asset.ownerId())) {
            throw notFound();
        }

        JobRow job = findLatestJobForAssetForUpdate(assetId);
        Instant now = now();
        if (job == null) {
            throw conflict("该资源没有可取消的处理任务");
        }
        if (isTerminal(job.status())) {
            return new MediaExecutionResponse(job.status());
        }

        jdbcTemplate.update("""
                update media_processing_job
                   set status = ?, lease_token_hash = null, lease_until = null,
                       candidate_key = null, candidate_url = null, candidate_sha256 = null,
                       candidate_type = null, candidate_size = null,
                       candidate_width = null, candidate_height = null,
                       updated_at = ?
                 where job_id = ?
                """,
                STATUS_CANCELLED,
                timestamp(now),
                job.jobId());
        jdbcTemplate.update("update media_asset set processing_status = ?, updated_at = ? where asset_id = ? and owner_id = ?",
                PROCESSING_CANCELLED, timestamp(now), assetId, ownerId);
        return new MediaExecutionResponse(STATUS_CANCELLED);
    }

    /** A user retry is a new version; delayed callbacks keep their original immutable job id. */
    @Transactional
    public void retryOwned(String assetId, Long ownerId) {
        AssetRow asset = findAsset(assetId);
        if (asset == null || ownerId == null || !ownerId.equals(asset.ownerId())) throw notFound();
        JobRow previous = findLatestJobForAssetForUpdate(assetId);
        if (previous == null || !(STATUS_FAILED.equals(previous.status()) || STATUS_CANCELLED.equals(previous.status()))
                || !"READY".equals(asset.status())) throw conflict("媒体任务当前不可重试");
        int version = Math.addExact(previous.jobVersion(), 1);
        String jobId = UUID.randomUUID().toString();
        Instant now = now();
        jdbcTemplate.update("""
                insert into media_processing_job(job_id,asset_id,job_version,preset,processor_version,status,attempts,created_at,updated_at)
                values (?,?,?,?,?,'PENDING',0,?,?)
                """, jobId, assetId, version, previous.preset(), previous.processorVersion(), timestamp(now), timestamp(now));
        jdbcTemplate.update("""
                insert into media_request_outbox(event_id,asset_id,job_id,job_version,preset,processor_version,occurred_at,status,attempts,available_at)
                values (?,?,?,?,?,?,?,'PENDING',0,?)
                """, UUID.randomUUID().toString(), assetId, jobId, version, previous.preset(), previous.processorVersion(), timestamp(now), timestamp(now));
        updateAssetProcessingStatus(assetId, STATUS_PENDING, now);
    }

    /** Returns the current immutable job row under a write lock for the coordinator. */
    @Transactional
    public JobLeaseSnapshot lockForResult(String jobId, int jobVersion, String leaseToken) {
        if (jobVersion < 1 || blank(leaseToken)) {
            throw badRequest("结果请求不合法");
        }
        JobRow job = findJobForUpdate(jobId);
        if (job == null) {
            throw notFound();
        }
        requireVersion(job, jobVersion);
        if (STATUS_SUCCEEDED.equals(job.status())) {
            if (!sameLease(job.leaseTokenHash(), hashLeaseToken(leaseToken))) throw conflict("媒体租约已失效");
        } else {
            requireCurrentLease(job, leaseToken, now());
        }
        AssetRow asset = findAsset(job.assetId());
        if (asset == null) {
            throw notFound();
        }
        return new JobLeaseSnapshot(job.jobId(), job.assetId(), job.jobVersion(), job.preset(),
                job.processorVersion(), job.status(), job.leaseTokenHash(), job.leaseUntil(),
                job.candidateKey(), job.candidateUrl(), job.candidateSha256(), job.candidateType(),
                job.candidateSize(), job.candidateWidth(), job.candidateHeight(), job.outputKey(),
                job.outputUrl(), job.outputSha256(), job.outputType(), job.outputSize(),
                job.outputWidth(), job.outputHeight(), asset.provider(), asset.sha256());
    }

    /** Reserves candidate metadata while the worker lease is still current. */
    @Transactional
    public Reservation reserveResult(String jobId, int jobVersion, String leaseToken,
                                     CandidateMetadata candidate) {
        if (candidate == null) {
            throw badRequest("结果元数据不合法");
        }
        JobRow job = findJobForUpdate(jobId);
        if (job == null) {
            throw notFound();
        }
        requireVersion(job, jobVersion);
        String providedHash = hashLeaseToken(leaseToken);
        if (STATUS_SUCCEEDED.equals(job.status())) {
            if (sameLease(job.leaseTokenHash(), providedHash)
                    && sameOutput(job.outputSha256(), job.outputType(), job.outputSize(),
                    job.outputWidth(), job.outputHeight(), candidate)
                    && Objects.equals(job.outputKey(), candidate.key())
                    && Objects.equals(job.outputUrl(), candidate.url())) {
                return new Reservation(true, candidate);
            }
            throw conflict("媒体任务结果已冲突");
        }
        if (isTerminal(job.status())) {
            throw conflict("媒体任务已结束");
        }
        requireCurrentLeaseHash(job, providedHash, now());

        if (job.candidateSha256() != null && !sameCandidate(job, candidate)) {
            throw conflict("媒体任务结果已冲突");
        }
        if (job.candidateSha256() == null) {
            Instant now = now();
            jdbcTemplate.update("""
                    update media_processing_job
                       set candidate_key = ?, candidate_url = ?, candidate_sha256 = ?,
                           candidate_type = ?, candidate_size = ?, candidate_width = ?,
                           candidate_height = ?, updated_at = ?
                     where job_id = ?
                    """,
                    candidate.key(), candidate.url(), candidate.sha256(), candidate.contentType(),
                    candidate.byteSize(), candidate.width(), candidate.height(), timestamp(now), job.jobId());
        }
        return new Reservation(false, candidate);
    }

    /** Accepts an object after its SQL reservation and external write complete. */
    @Transactional
    public MediaExecutionResponse acceptResult(String jobId, int jobVersion, String leaseToken,
                                               CandidateMetadata candidate) {
        if (candidate == null) {
            throw badRequest("结果元数据不合法");
        }
        JobRow job = findJobForUpdate(jobId);
        if (job == null) {
            throw notFound();
        }
        requireVersion(job, jobVersion);
        String providedHash = hashLeaseToken(leaseToken);
        if (STATUS_SUCCEEDED.equals(job.status())) {
            if (sameLease(job.leaseTokenHash(), providedHash)
                    && sameOutput(job.outputSha256(), job.outputType(), job.outputSize(),
                    job.outputWidth(), job.outputHeight(), candidate)
                    && Objects.equals(job.outputKey(), candidate.key())
                    && Objects.equals(job.outputUrl(), candidate.url())) {
                return new MediaExecutionResponse(STATUS_SUCCEEDED);
            }
            throw conflict("媒体任务结果已冲突");
        }
        if (isTerminal(job.status())) {
            throw conflict("媒体任务已结束");
        }
        requireCurrentLeaseHash(job, providedHash, now());
        if (!sameCandidate(job, candidate)) {
            throw conflict("媒体任务结果预留不匹配");
        }

        Instant now = now();
        jdbcTemplate.update("""
                update media_processing_job
                   set status = ?, output_key = ?, output_url = ?, output_sha256 = ?,
                       output_type = ?, output_size = ?, output_width = ?, output_height = ?,
                       candidate_key = null, candidate_url = null, candidate_sha256 = null,
                       candidate_type = null, candidate_size = null,
                       candidate_width = null, candidate_height = null,
                       error_code = null, updated_at = ?
                 where job_id = ?
                """,
                STATUS_SUCCEEDED,
                candidate.key(), candidate.url(), candidate.sha256(), candidate.contentType(),
                candidate.byteSize(), candidate.width(), candidate.height(), timestamp(now), job.jobId());
        updateAssetProcessingStatus(job.assetId(), PROCESSING_SUCCEEDED, now);
        return new MediaExecutionResponse(STATUS_SUCCEEDED);
    }

    private void markTimedOut(JobRow job, Instant now) {
        jdbcTemplate.update("""
                update media_processing_job
                   set status = ?, error_code = 'TIMEOUT', lease_token_hash = null,
                       lease_until = null, candidate_key = null, candidate_url = null,
                       candidate_sha256 = null, candidate_type = null, candidate_size = null,
                       candidate_width = null, candidate_height = null, updated_at = ?
                 where job_id = ?
                """, STATUS_FAILED, timestamp(now), job.jobId());
        updateAssetProcessingStatus(job.assetId(), PROCESSING_FAILED, now);
    }

    private JobRow findJobForUpdate(String jobId) {
        if (blank(jobId)) {
            return null;
        }
        List<JobRow> rows = jdbcTemplate.query("""
                select job_id, asset_id, job_version, preset, processor_version, status, attempts,
                       lease_token_hash, lease_until, candidate_key, candidate_url, candidate_sha256,
                       candidate_type, candidate_size, candidate_width, candidate_height,
                       output_key, output_url, output_sha256, output_type, output_size,
                       output_width, output_height, error_code
                  from media_processing_job
                 where job_id = ?
                 for update
                """, this::mapJob, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JobRow findLatestJobForAssetForUpdate(String assetId) {
        // The immutable first job serializes owner actions without locking the asset
        // before a worker's job lock. Read the latest version only after this lock.
        jdbcTemplate.query("select job_id from media_processing_job where asset_id=? order by job_version asc limit 1 for update",
                (rs, row) -> rs.getString(1), assetId);
        List<JobRow> rows = jdbcTemplate.query("""
                select job_id, asset_id, job_version, preset, processor_version, status, attempts,
                       lease_token_hash, lease_until, candidate_key, candidate_url, candidate_sha256,
                       candidate_type, candidate_size, candidate_width, candidate_height,
                       output_key, output_url, output_sha256, output_type, output_size,
                       output_width, output_height, error_code
                  from media_processing_job
                 where asset_id = ?
                 order by job_version desc
                 limit 1
                 for update
                """, this::mapJob, assetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OutboxRow findOutbox(String eventId) {
        List<OutboxRow> rows = jdbcTemplate.query("""
                select event_id, asset_id, job_id, job_version, preset, processor_version
                  from media_request_outbox
                 where event_id = ?
                """, (rs, rowNum) -> new OutboxRow(
                rs.getString("event_id"), rs.getString("asset_id"), rs.getString("job_id"),
                rs.getInt("job_version"), rs.getString("preset"), rs.getString("processor_version")), eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AssetRow findAsset(String assetId) {
        List<AssetRow> rows = jdbcTemplate.query("""
                select asset_id, owner_id, provider, object_key, content_type, byte_size,
                       sha256, status, processing_status
                  from media_asset
                 where asset_id = ?
                """, (rs, rowNum) -> new AssetRow(
                rs.getString("asset_id"), rs.getLong("owner_id"), rs.getString("provider"),
                rs.getString("object_key"), rs.getString("content_type"), rs.getLong("byte_size"),
                rs.getString("sha256"), rs.getString("status"), rs.getString("processing_status")), assetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JobRow mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new JobRow(
                rs.getString("job_id"), rs.getString("asset_id"), rs.getInt("job_version"),
                rs.getString("preset"), rs.getString("processor_version"), rs.getString("status"),
                rs.getInt("attempts"), rs.getString("lease_token_hash"), instant(rs, "lease_until"),
                rs.getString("candidate_key"), rs.getString("candidate_url"), rs.getString("candidate_sha256"),
                rs.getString("candidate_type"), nullableLong(rs, "candidate_size"),
                nullableInt(rs, "candidate_width"), nullableInt(rs, "candidate_height"),
                rs.getString("output_key"), rs.getString("output_url"), rs.getString("output_sha256"),
                rs.getString("output_type"), nullableLong(rs, "output_size"),
                nullableInt(rs, "output_width"), nullableInt(rs, "output_height"), rs.getString("error_code"));
    }

    private void updateAssetProcessingStatus(String assetId, String processingStatus, Instant now) {
        jdbcTemplate.update("update media_asset set processing_status = ?, updated_at = ? where asset_id = ?",
                processingStatus, timestamp(now), assetId);
    }

    private boolean matches(JobRow job, MediaRequestEnvelope envelope) {
        return envelope.jobId().equals(job.jobId())
                && envelope.assetId().equals(job.assetId())
                && envelope.jobVersion() == job.jobVersion()
                && envelope.preset().equals(job.preset())
                && envelope.processorVersion().equals(job.processorVersion());
    }

    private boolean matches(OutboxRow outbox, MediaRequestEnvelope envelope) {
        return envelope.eventId().equals(outbox.eventId())
                && envelope.assetId().equals(outbox.assetId())
                && envelope.jobId().equals(outbox.jobId())
                && envelope.jobVersion() == outbox.jobVersion()
                && envelope.preset().equals(outbox.preset())
                && envelope.processorVersion().equals(outbox.processorVersion());
    }

    private void requireVersion(JobRow job, int jobVersion) {
        if (job.jobVersion() != jobVersion) {
            throw conflict("媒体任务版本冲突");
        }
    }

    private void requireCurrentLease(JobRow job, String leaseToken, Instant now) {
        requireCurrentLeaseHash(job, hashLeaseToken(leaseToken), now);
    }

    private void requireCurrentLeaseHash(JobRow job, String providedHash, Instant now) {
        if (!STATUS_PROCESSING.equals(job.status()) || !sameLease(job.leaseTokenHash(), providedHash)
                || job.leaseUntil() == null || !job.leaseUntil().isAfter(now)) {
            throw conflict("媒体租约已失效");
        }
    }

    private boolean sameCandidate(JobRow job, CandidateMetadata candidate) {
        return Objects.equals(job.candidateKey(), candidate.key())
                && Objects.equals(job.candidateUrl(), candidate.url())
                && Objects.equals(job.candidateSha256(), candidate.sha256())
                && Objects.equals(job.candidateType(), candidate.contentType())
                && Objects.equals(job.candidateSize(), candidate.byteSize())
                && Objects.equals(job.candidateWidth(), candidate.width())
                && Objects.equals(job.candidateHeight(), candidate.height());
    }

    private boolean sameOutput(String sha256, String contentType, Long byteSize, Integer width, Integer height,
                               CandidateMetadata candidate) {
        return Objects.equals(sha256, candidate.sha256())
                && Objects.equals(contentType, candidate.contentType())
                && Objects.equals(byteSize, candidate.byteSize())
                && Objects.equals(width, candidate.width())
                && Objects.equals(height, candidate.height());
    }

    private MediaClaimResponse terminal(String status) {
        return new MediaClaimResponse(status, null, null, null, null, null, null, null, null, null);
    }

    private boolean isTerminal(String status) {
        return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    private boolean isFailureCode(String errorCode) {
        return switch (errorCode == null ? "" : errorCode) {
            case "UNSUPPORTED_FORMAT", "ANIMATED_IMAGE", "MALFORMED_IMAGE", "INVALID_METADATA",
                    "IMAGE_LIMITS", "INPUT_TOO_LARGE", "OUTPUT_TOO_LARGE", "ENCODE", "TIMEOUT",
                    "INPUT_MISMATCH" -> true;
            default -> false;
        };
    }

    private String freshLeaseToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private String hashLeaseToken(String token) {
        if (blank(token) || token.length() > 512) {
            throw badRequest("媒体租约不合法");
        }
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private boolean sameLease(String expectedHash, String providedHash) {
        return expectedHash != null && MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                providedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static Duration requireLeaseDuration(Duration duration) {
        if (duration == null || duration.compareTo(MIN_LEASE) < 0 || duration.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("lease duration must be between 10 seconds and 10 minutes");
        }
        return duration;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体任务不存在");
    }

    private ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "媒体任务正在处理");
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record CandidateMetadata(
            String key,
            String url,
            String sha256,
            String contentType,
            long byteSize,
            int width,
            int height
    ) {
    }

    public record Reservation(boolean alreadyAccepted, CandidateMetadata candidate) {
    }

    public record JobLeaseSnapshot(
            String jobId,
            String assetId,
            int jobVersion,
            String preset,
            String processorVersion,
            String status,
            String leaseTokenHash,
            Instant leaseUntil,
            String candidateKey,
            String candidateUrl,
            String candidateSha256,
            String candidateType,
            Long candidateSize,
            Integer candidateWidth,
            Integer candidateHeight,
            String outputKey,
            String outputUrl,
            String outputSha256,
            String outputType,
            Long outputSize,
            Integer outputWidth,
            Integer outputHeight,
            String inputProvider,
            String inputSha256
    ) {
    }

    private record JobRow(
            String jobId,
            String assetId,
            int jobVersion,
            String preset,
            String processorVersion,
            String status,
            int attempts,
            String leaseTokenHash,
            Instant leaseUntil,
            String candidateKey,
            String candidateUrl,
            String candidateSha256,
            String candidateType,
            Long candidateSize,
            Integer candidateWidth,
            Integer candidateHeight,
            String outputKey,
            String outputUrl,
            String outputSha256,
            String outputType,
            Long outputSize,
            Integer outputWidth,
            Integer outputHeight,
            String errorCode
    ) {
    }

    private record OutboxRow(
            String eventId,
            String assetId,
            String jobId,
            int jobVersion,
            String preset,
            String processorVersion
    ) {
    }

    private record AssetRow(
            String assetId,
            Long ownerId,
            String provider,
            String objectKey,
            String contentType,
            long byteSize,
            String sha256,
            String status,
            String processingStatus
    ) {
    }
}
