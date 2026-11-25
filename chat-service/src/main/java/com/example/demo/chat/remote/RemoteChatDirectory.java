package com.example.demo.chat.remote;

import com.example.demo.chat.service.ChatDirectory;
import com.example.demo.chat.service.ChatDirectory.Decision;
import com.example.demo.chat.service.ChatDirectory.Purpose;
import com.example.demo.notifications.client.InternalServiceCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Chat's cross-process adapter for identity, profile, and contact ownership.
 *
 * <p>This class is deliberately not a Spring component. The core process uses
 * a local {@link ChatDirectory}; the isolated chat process creates this adapter
 * explicitly so importing the shared backend jar cannot silently register an
 * additional directory or core component scan.</p>
 */
public final class RemoteChatDirectory implements ChatDirectory {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteChatDirectory.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String AUTH_SESSION_PATH = "/api/v1/chat/realtime/session";
    private static final String PROFILES_PATH = "/internal/v1/users/public-profiles:batchGet";
    private static final String DECISIONS_PATH = "/internal/v1/social/contact-decisions";
    private static final String DECISION_BATCH_PATH = DECISIONS_PATH + ":batchGet";
    private static final int LOCK_BUCKETS = 256;
    private static final Duration MAX_DECISION_LIFETIME = Duration.ofSeconds(2);

    private final JdbcTemplate jdbc;
    private final RestClient restClient;
    private final String internalServiceToken;

    /**
     * Creates an adapter with bounded network timeouts and a validated private
     * service credential.
     *
     * @param jdbc chat-database access used for local account lock buckets
     * @param coreUrl private URL of the core identity/social owner
     * @param internalServiceToken dedicated chat service credential
     */
    public RemoteChatDirectory(JdbcTemplate jdbc, String coreUrl, String internalServiceToken) {
        this(jdbc, timedClient().baseUrl(validateCoreUrl(coreUrl)).build(),
                InternalServiceCredential.requireStrongConfiguration(internalServiceToken == null
                        ? "" : internalServiceToken.trim()));
    }

    RemoteChatDirectory(JdbcTemplate jdbc, RestClient restClient, String internalServiceToken) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.internalServiceToken = InternalServiceCredential.requireStrongConfiguration(
                internalServiceToken == null ? "" : internalServiceToken.trim());
    }

    /**
     * Verifies the caller's bearer token through the core owner. A rejected
     * token returns {@code null}; a failed or unavailable owner is surfaced as
     * HTTP 503 so the chat process never treats an unvalidated identity as
     * authenticated.
     */
    public Long verifyBearer(String authorization) {
        if (!validBearer(authorization)) return null;
        try {
            SessionResponse response = restClient.get()
                    .uri(AUTH_SESSION_PATH)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .headers(this::addRequestId)
                    .retrieve()
                    .body(SessionResponse.class);
            if (response == null || response.userId() <= 0
                    || response.expiresAt() <= Instant.now().getEpochSecond()) {
                return null;
            }
            return response.userId();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()
                    || exception.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
                return null;
            }
            throw unavailable("认证源");
        } catch (RuntimeException exception) {
            LOG.warn("chat_bearer_verification_failed error={}", exception.getClass().getSimpleName());
            throw unavailable("认证源");
        }
    }

    @Override
    public void lockAccounts(Collection<Long> accountIds) {
        List<Long> ids = normalizeIds(accountIds, 100);
        if (ids.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Chat account locks require a transaction");
        }
        int[] buckets = ids.stream().mapToInt(id -> Math.floorMod(Long.hashCode(id), LOCK_BUCKETS))
                .distinct().sorted().toArray();
        try {
            for (int bucket : buckets) {
                jdbc.update("""
                        INSERT INTO chat_write_locks (bucket_id) VALUES (?)
                        ON DUPLICATE KEY UPDATE bucket_id = bucket_id
                        """, bucket);
            }
        } catch (DataAccessException exception) {
            LOG.warn("chat_account_lock_failed error={}", exception.getClass().getSimpleName());
            throw unavailable("聊天数据库");
        }
    }

    @Override
    public Map<Long, String> displayNames(Collection<Long> accountIds) {
        List<Long> ids = normalizeIds(accountIds, 100);
        if (ids.isEmpty()) return Map.of();
        try {
            var request = new ProfileRequest(ids.stream().map(String::valueOf).toList());
            ProfileBatch response = post(PROFILES_PATH, request, ProfileBatch.class);
            if (response == null || response.profiles() == null || response.missingIds() == null) {
                throw unavailable("资料源");
            }
            var unmatched = new LinkedHashSet<>(ids);
            Map<Long, String> result = new LinkedHashMap<>();
            for (PublicProfile profile : response.profiles()) {
                if (profile == null) throw unavailable("资料源");
                Long id = parsePositive(profile.id());
                if (!unmatched.remove(id)) throw unavailable("资料源");
                String name = firstText(profile.nickname(), profile.username());
                result.put(id, name == null ? "用户" : name);
            }
            for (String missingId : response.missingIds()) {
                if (!unmatched.remove(parsePositive(missingId))) throw unavailable("资料源");
            }
            if (!unmatched.isEmpty()) throw unavailable("资料源");
            return Map.copyOf(result);
        } catch (RestClientResponseException exception) {
            throw unavailable("资料源");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.warn("chat_profile_lookup_failed error={}", exception.getClass().getSimpleName());
            throw unavailable("资料源");
        }
    }

    @Override
    public Map<Long, Decision> decisions(long actorId, Collection<Long> peerIds, Purpose purpose,
                                         UUID operationId) {
        if (actorId <= 0 || purpose == null || operationId == null) throw unavailable("联系授权源");
        List<Long> peers = normalizeIds(peerIds, 100);
        if (peers.isEmpty()) return Map.of();
        if (peers.contains(actorId)) throw unavailable("联系授权源");
        String actor = Long.toString(actorId);
        try {
            List<ContactDecision> responses;
            if (peers.size() == 1) {
                var request = new ContactRequest(actor, peers.getFirst().toString(), purpose.name(), operationId);
                ContactDecision response = post(DECISIONS_PATH, request, ContactDecision.class);
                if (response == null) throw unavailable("联系授权源");
                responses = List.of(response);
            } else {
                var request = new ContactBatchRequest(actor, peers.stream().map(String::valueOf).toList(),
                        purpose.name(), operationId);
                var batch = post(DECISION_BATCH_PATH, request, ContactBatch.class);
                if (batch == null || batch.decisions() == null) throw unavailable("联系授权源");
                responses = batch.decisions();
            }
            return validateDecisions(actor, peers, purpose, operationId, responses);
        } catch (RestClientResponseException exception) {
            throw unavailable("联系授权源");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.warn("chat_contact_decision_failed error={}", exception.getClass().getSimpleName());
            throw unavailable("联系授权源");
        }
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return restClient.post().uri(path)
                .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                .headers(this::addRequestId).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(responseType);
    }

    private static Map<Long, Decision> validateDecisions(String actor, List<Long> peers, Purpose purpose,
                                                         UUID operationId, List<ContactDecision> responses) {
        if (responses.size() != peers.size() || responses.getFirst() == null) throw unavailable("联系授权源");
        Instant now = Instant.now();
        Instant evaluatedAt = responses.getFirst().evaluatedAt();
        Instant expiry = responses.getFirst().expiresAt();
        if (evaluatedAt == null || expiry == null || !expiry.isAfter(now) || !expiry.isAfter(evaluatedAt)
                || evaluatedAt.isAfter(now.plus(MAX_DECISION_LIFETIME))
                || Duration.between(evaluatedAt, expiry).compareTo(MAX_DECISION_LIFETIME) > 0) {
            throw unavailable("联系授权源");
        }
        var unmatched = new LinkedHashSet<>(peers);
        var decisionIds = new LinkedHashSet<UUID>();
        Map<Long, Decision> result = new LinkedHashMap<>();
        for (ContactDecision response : responses) {
            if (response == null || response.decisionId() == null || !decisionIds.add(response.decisionId())
                    || !actor.equals(response.actorId()) || !purpose.name().equals(response.purpose())
                    || !operationId.equals(response.operationId()) || !evaluatedAt.equals(response.evaluatedAt())
                    || !expiry.equals(response.expiresAt()) || response.mutual() == null
                    || response.contactAllowed() == null || response.blockedByActor() == null
                    || (response.mutual() && Boolean.FALSE.equals(response.friend()))
                    || (response.blockedByActor() && response.contactAllowed())) {
                throw unavailable("联系授权源");
            }
            Long peer = parsePositive(response.peerId());
            if (!unmatched.remove(peer)) throw unavailable("联系授权源");
            boolean friend = response.friend() == null ? response.mutual() : response.friend();
            result.put(peer, new Decision(response.mutual(), response.contactAllowed(), response.blockedByActor(), expiry,
                    friend));
        }
        return Collections.unmodifiableMap(result);
    }

    private void addRequestId(HttpHeaders headers) {
        String requestId = MDC.get("requestId");
        if (requestId != null && requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
            headers.set(REQUEST_ID_HEADER, requestId);
        }
    }

    private static RestClient.Builder timedClient() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory);
    }

    private static String validateCoreUrl(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        try {
            URI uri = URI.create(value);
            if ((uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http")
                    || uri.getScheme().equalsIgnoreCase("https")))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw new IllegalArgumentException("CHAT_CORE_URL must be an HTTP(S) URL without credentials");
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CHAT_CORE_URL must be an HTTP(S) URL without credentials", exception);
        }
    }

    private static boolean validBearer(String authorization) {
        return authorization != null && authorization.length() <= 8192
                && authorization.startsWith("Bearer ") && authorization.substring(7).trim().length() > 0
                && authorization.indexOf('\r') < 0 && authorization.indexOf('\n') < 0;
    }

    private static List<Long> normalizeIds(Collection<Long> values, int limit) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) throw unavailable("联系授权源");
            ids.add(value);
            if (ids.size() > limit) throw unavailable("联系授权源");
        }
        return ids.stream().sorted().toList();
    }

    private static Long parsePositive(String value) {
        try {
            long id = Long.parseLong(value);
            return id > 0 && Long.toString(id).equals(value) ? id : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static ResponseStatusException unavailable(String source) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                source + "暂不可用，请重试");
    }

    private record SessionResponse(long userId, long expiresAt) {}

    private record ProfileRequest(List<String> userIds) {}

    private record ProfileBatch(List<PublicProfile> profiles, List<String> missingIds) {}

    private record PublicProfile(String id, String username, String nickname, String avatarUrl) {}

    private record ContactRequest(String actorId, String peerId, String purpose, UUID operationId) {}

    private record ContactBatchRequest(String actorId, List<String> peerIds, String purpose, UUID operationId) {}
    private record ContactBatch(List<ContactDecision> decisions) {}
    private record ContactDecision(UUID decisionId, String actorId, String peerId, String purpose, UUID operationId,
                                   Boolean mutual, Boolean contactAllowed, Boolean blockedByActor,
                                   Instant evaluatedAt, Instant expiresAt, Boolean friend) {}
}
