package com.example.demo.ai.summary.service;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryFailureCode;
import com.example.demo.ai.service.AiSummaryResult;
import com.example.demo.ai.summary.dto.SummaryView;
import com.example.demo.ai.summary.entity.SummaryAttempt;
import com.example.demo.ai.summary.entity.SummaryBudget;
import com.example.demo.ai.summary.entity.SummaryBudgetId;
import com.example.demo.ai.summary.entity.SummaryTask;
import com.example.demo.ai.summary.entity.SummaryRequest;
import com.example.demo.ai.summary.entity.SummaryRequestId;
import com.example.demo.ai.summary.repo.SummaryAttemptRepository;
import com.example.demo.ai.summary.repo.SummaryBudgetRepository;
import com.example.demo.ai.summary.repo.SummaryTaskRepository;
import com.example.demo.ai.summary.repo.SummaryRequestRepository;
import com.example.demo.governance.service.GovernanceService;
import com.example.demo.threads.dto.ThreadSummarySnapshot;
import com.example.demo.threads.service.ThreadSummaryContentPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short SQL transactions own identity, budget and publication; model I/O is outside them. */
@Service
public class SummaryTaskService {
    private static final Set<String> OPEN = Set.of("PENDING", "PROCESSING");
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_INPUT_CHARS = 12_000;
    private final SummaryTaskRepository tasks;
    private final SummaryAttemptRepository attempts;
    private final SummaryBudgetRepository budgets;
    private final SummaryRequestRepository requests;
    private final ThreadSummaryContentPort content;
    private final GovernanceService governance;
    private final SummaryTaskPublisher publisher;
    private final SummaryCancellationRegistry cancellation;
    private final AiService ai;
    private final TransactionTemplate tx;
    private final long dailyBudget;
    private final int outputTokens;

    public SummaryTaskService(SummaryTaskRepository tasks, SummaryAttemptRepository attempts,
                              SummaryBudgetRepository budgets, SummaryRequestRepository requests, ThreadSummaryContentPort content,
                              GovernanceService governance, SummaryTaskPublisher publisher,
                              SummaryCancellationRegistry cancellation, AiService ai,
                              PlatformTransactionManager transactions,
                              @Value("${app.ai.summary.daily-token-budget:50000}") long dailyBudget,
                              @Value("${app.ai.summary.max-output-tokens:512}") int outputTokens) {
        this.tasks = tasks; this.attempts = attempts; this.budgets = budgets; this.requests = requests;
        this.content = content; this.governance = governance; this.publisher = publisher;
        this.cancellation = cancellation; this.ai = ai; this.tx = new TransactionTemplate(transactions);
        if (dailyBudget < 1 || dailyBudget > 100_000_000L || outputTokens < 1 || outputTokens > 2048) {
            throw new IllegalArgumentException("Invalid summary budget configuration");
        }
        this.dailyBudget = dailyBudget; this.outputTokens = outputTokens;
    }

    public SummaryView start(Long threadId, Long actor, String requestKey, boolean force) {
        String key = uuid(requestKey);
        return tx.execute(ignored -> {
            governance.lockActor(actor);
            ThreadSummarySnapshot thread = normal(content.lock(threadId));
            requireManage(actor, thread);
            SummaryRequest replay = requests.findById(new SummaryRequestId(actor, key)).orElse(null);
            if (replay != null) {
                if (!Objects.equals(replay.getThreadId(), threadId) || replay.getContentVersion() != thread.contentVersion()
                        || replay.isForceRequested() != force) throw fail(HttpStatus.CONFLICT, "请求标识已用于其他内容版本");
                SummaryTask original = tasks.findForUpdate(replay.getTaskId()).orElseThrow(() -> fail(HttpStatus.CONFLICT, "摘要任务不存在"));
                return view(thread, original, actor);
            }
            SummaryTask current = thread.taskId() == null ? null : tasks.findForUpdate(thread.taskId()).orElse(null);
            if (current != null && current.getContentVersion() == thread.contentVersion()) {
                if (OPEN.contains(current.getStatus())) throw fail(HttpStatus.CONFLICT, "请先取消正在运行的摘要任务");
                if (!force && "COMPLETED".equals(current.getStatus())) {
                    bind(actor, key, current, false);
                    return view(thread, current, actor);
                }
            }
            if (!publisher.available()) throw fail(HttpStatus.SERVICE_UNAVAILABLE, "摘要生成服务暂不可用");
            if (tasks.countOpen(actor) >= 2) throw fail(HttpStatus.TOO_MANY_REQUESTS, "最多同时生成两个摘要");
            validateInput(thread.content());
            Instant now = now();
            SummaryTask task = new SummaryTask();
            task.setTaskId(UUID.randomUUID().toString()); task.setThreadId(threadId);
            task.setContentVersion(thread.contentVersion()); task.setTriggeredBy(actor); task.setRequestKey(key);
            task.setForceRequested(force); task.setInputSha256(hash(thread.content())); task.setInputChars(thread.content().length());
            task.setStatus("PENDING"); task.setAvailableAt(now); task.setCreatedAt(now); task.setUpdatedAt(now);
            String requestId = MDC.get("requestId");
            task.setRequestId(requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.substring(0, Math.min(128, requestId.length())));
            tasks.saveAndFlush(task);
            bind(actor, key, task, force);
            if (!content.assign(threadId, thread.contentVersion(), task.getTaskId())) throw fail(HttpStatus.CONFLICT, "帖子内容已变化");
            afterCommit(() -> publisher.publish(task.getTaskId()));
            return view(thread, task, actor);
        });
    }

    public SummaryView read(Long threadId, Long actor) {
        return tx.execute(ignored -> {
            ThreadSummarySnapshot thread = normal(content.read(threadId));
            SummaryTask task = thread.taskId() == null ? null : tasks.findById(thread.taskId()).orElse(null);
            return view(thread, task, actor);
        });
    }

    public SummaryView readTask(String taskId, Long actor) {
        String id = uuid(taskId);
        return tx.execute(ignored -> {
            SummaryTask task = tasks.findById(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "摘要任务不存在"));
            return view(normal(content.read(task.getThreadId())), task, actor);
        });
    }

    public SummaryView cancel(Long threadId, String taskId, Long actor) {
        String id = uuid(taskId);
        return tx.execute(ignored -> {
            governance.lockActor(actor);
            ThreadSummarySnapshot thread = normal(content.lock(threadId));
            requireManage(actor, thread);
            SummaryTask task = tasks.findForUpdate(id).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "摘要任务不存在"));
            if (!task.getThreadId().equals(threadId)) throw fail(HttpStatus.NOT_FOUND, "摘要任务不存在");
            if (OPEN.contains(task.getStatus())) {
                Instant now = now();
                finish(task, "CANCELLED", "CANCELLED", now);
                content.update(threadId, task.getContentVersion(), id, "CANCELLED", null);
                afterCommit(() -> cancellation.cancel(id));
            }
            return view(thread, task, actor);
        });
    }

    public Claim claim(String taskId) {
        String id = uuid(taskId);
        // Only detached scalar identity escapes this transaction; do not hydrate a stale task before locking.
        SummaryTaskRepository.Identity identity = tasks.identity(id).orElse(null);
        if (identity == null) return null;
        Long actor = identity.getActor();
        return tx.execute(ignored -> {
            governance.lockActor(actor);
            Long threadId = identity.getThreadId();
            ThreadSummarySnapshot thread = content.lock(threadId);
            SummaryTask task = tasks.findForUpdate(id).orElse(null);
            Instant now = now();
            if (task == null || !OPEN.contains(task.getStatus())) return null;
            if (!validContent(thread, task)) { finish(task, "SUPERSEDED", "CONTENT_CHANGED", now); return null; }
            if (!canManage(actor, thread)) { terminal(task, "PERMISSION_REVOKED", now); return null; }
            if ("PROCESSING".equals(task.getStatus()) && task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)) return null;
            if ("PENDING".equals(task.getStatus()) && task.getAvailableAt().isAfter(now)) return null;
            if ("PROCESSING".equals(task.getStatus())) {
                attempts.findByTaskIdAndAttemptNumber(id, task.getAttempts()).ifPresent(previous -> {
                    previous.setStatus("EXPIRED"); previous.setErrorCode("LEASE_EXPIRED"); previous.setFinishedAt(now);
                });
            }
            if (task.getAttempts() >= MAX_ATTEMPTS) { terminal(task, "LEASE_EXPIRED", now); return null; }
            long reserved = (long) thread.content().getBytes(StandardCharsets.UTF_8).length + 2048L + outputTokens;
            SummaryBudget budget = budget(actor, LocalDate.ofInstant(now, ZoneOffset.UTC));
            if (budget.getChargedTokens() > dailyBudget - reserved) { terminal(task, "BUDGET_EXCEEDED", now); return null; }
            budget.setChargedTokens(Math.addExact(budget.getChargedTokens(), reserved));
            String owner = UUID.randomUUID().toString();
            task.setStatus("PROCESSING"); task.setAttempts(task.getAttempts() + 1); task.setLeaseOwner(owner);
            task.setLeaseUntil(now.plusSeconds(180)); task.setUpdatedAt(now); task.setErrorCode(null);
            SummaryAttempt attempt = new SummaryAttempt();
            attempt.setTaskId(id); attempt.setAttemptNumber(task.getAttempts()); attempt.setLeaseOwner(owner);
            attempt.setBudgetDay(budget.getId().getDay()); attempt.setReservedTokens(reserved); attempt.setChargedTokens(reserved);
            attempt.setStatus("PROCESSING"); attempt.setProvider(ai.summaryProviderName()); attempt.setModel(ai.summaryModelName());
            attempt.setStartedAt(now); attempts.save(attempt);
            content.update(threadId, task.getContentVersion(), id, "PROCESSING", null);
            return new Claim(id, actor, threadId, task.getContentVersion(), task.getAttempts(), owner, thread.content(), outputTokens);
        });
    }

    public boolean owns(Claim claim) {
        return tasks.findById(claim.taskId()).map(task -> "PROCESSING".equals(task.getStatus())
                && claim.owner().equals(task.getLeaseOwner()) && task.getLeaseUntil() != null
                && task.getLeaseUntil().isAfter(now())).orElse(false);
    }

    public void settle(Claim claim, AiSummaryResult result, AiSummaryFailureCode failure) {
        tx.executeWithoutResult(ignored -> {
            governance.lockActor(claim.actor());
            ThreadSummarySnapshot thread = content.lock(claim.threadId());
            SummaryTask task = tasks.findForUpdate(claim.taskId()).orElse(null);
            SummaryAttempt attempt = attempts.findByTaskIdAndAttemptNumber(claim.taskId(), claim.attempt()).orElse(null);
            if (task == null || attempt == null || !claim.owner().equals(attempt.getLeaseOwner())) return;
            // A stale result may reconcile real usage, but cannot publish or mutate a newer lease.
            boolean abandoned = "CANCELLED".equals(attempt.getStatus()) || "SUPERSEDED".equals(attempt.getStatus())
                    || "EXPIRED".equals(attempt.getStatus());
            if (abandoned && result == null) return;
            if (!abandoned && !"PROCESSING".equals(attempt.getStatus()) && !"EXPIRED".equals(attempt.getStatus())) return;
            Instant now = now();
            attempt.setFinishedAt(now); attempt.setStatus(result == null ? "FAILED" : "COMPLETED");
            attempt.setDurationMillis(Math.max(0, java.time.Duration.between(attempt.getStartedAt(), now).toMillis()));
            attempt.setErrorCode(result == null && failure != null ? failure.name() : null);
            if (result != null) {
                attempt.setProvider(result.provider()); attempt.setModel(result.model()); attempt.setResponseModel(result.responseModel());
                attempt.setProviderRequestId(result.providerRequestId()); attempt.setProviderResponseId(result.providerResponseId());
                attempt.setInputTokens(result.inputTokens()); attempt.setOutputTokens(result.outputTokens()); attempt.setDurationMillis(result.durationMillis());
                if (result.inputTokens() != null && result.outputTokens() != null) {
                    long actual = Math.addExact(result.inputTokens(), result.outputTokens());
                    SummaryBudget budget = budget(claim.actor(), attempt.getBudgetDay());
                    budget.setChargedTokens(Math.addExact(budget.getChargedTokens(), actual - attempt.getChargedTokens()));
                    attempt.setChargedTokens(actual); attempt.setUsageEstimated(false);
                }
            }
            if (!"PROCESSING".equals(task.getStatus()) || !claim.owner().equals(task.getLeaseOwner())) return;
            if (!validContent(thread, task)) { finish(task, "SUPERSEDED", "CONTENT_CHANGED", now); return; }
            if (!canManage(claim.actor(), thread)) { terminal(task, "PERMISSION_REVOKED", now); return; }
            if (task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)) {
                if (task.getAttempts() < MAX_ATTEMPTS) {
                    finish(task, "PENDING", "LEASE_EXPIRED", now);
                    task.setAvailableAt(now.plusSeconds(1L << task.getAttempts()));
                    content.update(claim.threadId(), claim.version(), claim.taskId(), "PENDING", null);
                } else terminal(task, "LEASE_EXPIRED", now);
                return;
            }
            if (result != null) {
                finish(task, "COMPLETED", null, now); task.setSummary(result.summary());
                content.update(claim.threadId(), claim.version(), claim.taskId(), "COMPLETED", result.summary());
            } else if (retryable(failure) && task.getAttempts() < MAX_ATTEMPTS) {
                finish(task, "PENDING", failure.name(), now);
                task.setAvailableAt(now.plusSeconds(1L << task.getAttempts()));
                content.update(claim.threadId(), claim.version(), claim.taskId(), "PENDING", null);
            } else {
                terminal(task, failure == null ? "UPSTREAM" : failure.name(), now);
            }
        });
    }

    private SummaryBudget budget(Long actor, LocalDate day) {
        SummaryBudgetId id = new SummaryBudgetId(actor, day);
        return budgets.findById(id).orElseGet(() -> { SummaryBudget b = new SummaryBudget(); b.setId(id); return budgets.save(b); });
    }

    private void bind(Long actor, String key, SummaryTask task, boolean force) {
        SummaryRequest request = new SummaryRequest();
        request.setId(new SummaryRequestId(actor, key)); request.setTaskId(task.getTaskId()); request.setThreadId(task.getThreadId());
        request.setContentVersion(task.getContentVersion()); request.setForceRequested(force); request.setCreatedAt(now());
        requests.save(request);
    }

    private void terminal(SummaryTask task, String error, Instant now) {
        finish(task, "FAILED", error, now);
        content.update(task.getThreadId(), task.getContentVersion(), task.getTaskId(), "FAILED", null);
    }

    private void finish(SummaryTask task, String status, String error, Instant now) {
        if (Set.of("FAILED", "CANCELLED", "SUPERSEDED").contains(status)) {
            attempts.closeActive(task.getTaskId(), status, error, now);
        }
        task.setStatus(status); task.setErrorCode(error); task.setSummary(null);
        task.setLeaseOwner(null); task.setLeaseUntil(null); task.setUpdatedAt(now);
    }

    private SummaryView view(ThreadSummarySnapshot thread, SummaryTask task, Long actor) {
        boolean manage = canManage(actor, thread);
        if (task == null) return new SummaryView(thread.id(), null, thread.contentVersion(), null, "IDLE", "", manage,
                0, null, null, null, null, null, true, 0, null, null);
        SummaryAttempt last = attempts.findByTaskIdAndAttemptNumber(task.getTaskId(), task.getAttempts()).orElse(null);
        boolean current = task.getContentVersion() == thread.contentVersion() && Objects.equals(task.getTaskId(), thread.taskId());
        // start() has just assigned the pointer via JDBC; its pre-write immutable snapshot is expected.
        boolean sameVersion = task.getContentVersion() == thread.contentVersion();
        String status = sameVersion ? task.getStatus() : "SUPERSEDED";
        String summary = current && "COMPLETED".equals(status) && task.getSummary() != null ? task.getSummary() : "";
        return new SummaryView(thread.id(), task.getTaskId(), thread.contentVersion(), task.getContentVersion(), status, summary,
                manage, task.getAttempts(), sameVersion ? task.getErrorCode() : "CONTENT_CHANGED",
                last == null ? null : last.getProvider(), last == null ? null : last.getModel(),
                last == null ? null : last.getInputTokens(), last == null ? null : last.getOutputTokens(),
                last == null || attempts.countByTaskIdAndUsageEstimatedTrue(task.getTaskId()) > 0,
                attempts.charged(task.getTaskId()), task.getRequestId(), task.getUpdatedAt());
    }

    private boolean validContent(ThreadSummarySnapshot thread, SummaryTask task) {
        return thread != null && "NORMAL".equals(thread.status()) && thread.contentVersion() == task.getContentVersion()
                && task.getTaskId().equals(thread.taskId()) && hash(thread.content()).equals(task.getInputSha256());
    }
    private boolean canManage(Long actor, ThreadSummarySnapshot thread) {
        if (actor == null || thread == null) return false;
        if (actor.equals(thread.authorId())) return true;
        GovernanceService.Access access = governance.access(actor);
        return access.admin() || access.moderatedSectionIds().contains(thread.sectionId());
    }
    private void requireManage(Long actor, ThreadSummarySnapshot thread) {
        if (!canManage(actor, thread)) throw fail(HttpStatus.FORBIDDEN, "仅作者或当前分区管理者可生成摘要");
    }
    private static ThreadSummarySnapshot normal(ThreadSummarySnapshot thread) {
        if (thread == null || !"NORMAL".equals(thread.status())) throw fail(HttpStatus.NOT_FOUND, "帖子不存在");
        return thread;
    }
    private static void validateInput(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_INPUT_CHARS) throw fail(HttpStatus.BAD_REQUEST, "摘要正文需在1到12000字符之间");
    }
    private static String uuid(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (RuntimeException ignored) { throw fail(HttpStatus.BAD_REQUEST, "需要有效的UUID请求标识"); }
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static boolean retryable(AiSummaryFailureCode code) {
        return code == AiSummaryFailureCode.TIMEOUT || code == AiSummaryFailureCode.TRANSPORT
                || code == AiSummaryFailureCode.RATE_LIMIT || code == AiSummaryFailureCode.UPSTREAM;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static ResponseStatusException fail(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    public record Claim(String taskId, Long actor, Long threadId, long version, int attempt, String owner, String input, int outputTokens) { }
}
