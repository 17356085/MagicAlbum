package com.example.demo.ai.summary;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryFailureCode;
import com.example.demo.ai.service.AiSummaryResult;
import com.example.demo.ai.summary.dto.SummaryView;
import com.example.demo.ai.summary.repo.SummaryTaskRepository;
import com.example.demo.ai.summary.service.SummaryTaskPublisher;
import com.example.demo.ai.summary.service.SummaryTaskService;
import com.example.demo.ai.summary.service.SummaryTaskRunner;
import com.example.demo.ai.service.AiSummaryCall;
import com.example.demo.ai.service.AiSummaryException;
import com.example.demo.governance.entity.CommunityModeratorScope;
import com.example.demo.governance.repo.CommunityModeratorScopeRepository;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Committed SQL readbacks, not a test-wide rollback transaction. H2 is separate from real MySQL acceptance. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:m4-summary-tasks;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.jpa.show-sql=false", "app.ai.summary.daily-token-budget=50000"
})
class SummaryTaskIntegrationTest {
    @Autowired SummaryTaskService service;
    @Autowired SummaryTaskRunner runner;
    @Autowired SummaryTaskRepository taskRows;
    @Autowired ThreadRepository threads;
    @Autowired ThreadService threadService;
    @Autowired SectionRepository sections;
    @Autowired UserRepository users;
    @Autowired CommunityModeratorScopeRepository scopes;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SummaryTaskPublisher publisher;
    @MockitoBean AiService ai;
    Long owner;
    Long stranger;
    Section section;
    Thread thread;

    @BeforeEach
    void fixture() {
        when(publisher.available()).thenReturn(true);
        when(ai.summaryProviderName()).thenReturn("controlled-test");
        when(ai.summaryModelName()).thenReturn("controlled-model");
        owner = user(); stranger = user();
        section = new Section(); section.setName("summary"); section.setSlug(UUID.randomUUID().toString());
        section = sections.saveAndFlush(section);
        thread = thread("这是需要总结的正文，包含明确的主题与结论。");
    }

    @Test
    void completedResultAndUsageAreDurableAndReplayDoesNotChargeAgain() {
        String key = key();
        SummaryView pending = service.start(thread.getId(), owner, key, false);
        assertThat(pending.status()).isEqualTo("PENDING");
        var claim = service.claim(pending.taskId());
        assertThat(claim).isNotNull();
        assertThat(service.claim(pending.taskId())).isNull();
        service.settle(claim, result("实际结果", 31, 12), null);
        service.settle(claim, result("重复结果不得覆盖", 100, 100), null);
        SummaryView completed = service.read(thread.getId(), stranger);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.summary()).isEqualTo("实际结果");
        assertThat(completed.canManage()).isFalse();
        assertThat(completed.chargedTokens()).isEqualTo(43);
        assertThat(completed.usageEstimated()).isFalse();
        assertThat(completed.provider()).isEqualTo("controlled-test");
        assertThat(service.start(thread.getId(), owner, key, false).taskId()).isEqualTo(pending.taskId());
        String cacheHitKey = key();
        assertThat(service.start(thread.getId(), owner, cacheHitKey, false).taskId()).isEqualTo(pending.taskId());
        Thread other = thread("other target");
        status(409, () -> service.start(other.getId(), owner, cacheHitKey, false));
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id=?", pending.taskId())).isEqualTo(1);
        assertThat(sqlLong("SELECT charged_tokens FROM ai_summary_budget WHERE user_id=?", owner)).isEqualTo(43);
        assertThat(jdbc.queryForObject("SELECT summary FROM threads WHERE id=?", String.class, thread.getId())).isEqualTo("实际结果");
        verify(publisher, times(1)).publish(pending.taskId());
    }

    @Test
    void idempotencyIsBoundToActorTargetVersionAndForce() {
        String request = key();
        var pending = service.start(thread.getId(), owner, request, false);
        status(409, () -> service.start(thread.getId(), owner, request, true));
        Thread another = thread("another");
        status(409, () -> service.start(another.getId(), owner, request, false));
        status(409, () -> service.start(thread.getId(), owner, key(), true));
        service.cancel(thread.getId(), pending.taskId(), owner);
        var next = service.start(thread.getId(), owner, key(), true);
        assertThat(next.taskId()).isNotEqualTo(pending.taskId());
        assertThat(next.chargedTokens()).isZero();
    }

    @Test
    void ordinaryReaderCannotManageAndCurrentModeratorScopeIsRecheckedBeforeCall() {
        assertThat(service.read(thread.getId(), stranger).status()).isEqualTo("IDLE");
        status(403, () -> service.start(thread.getId(), stranger, key(), false));
        scopes.saveAndFlush(new CommunityModeratorScope(stranger, section.getId()));
        var pending = service.start(thread.getId(), stranger, key(), false);
        scopes.deleteByUserId(stranger);
        assertThat(service.claim(pending.taskId())).isNull();
        var failed = service.readTask(pending.taskId(), owner);
        assertThat(failed.errorCode()).isEqualTo("PERMISSION_REVOKED");
        assertThat(failed.chargedTokens()).isZero();
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id=?", pending.taskId())).isZero();
    }

    @Test
    void pendingCancellationIsIdempotentAndDoesNotReserveBudget() {
        var pending = start();
        status(403, () -> service.cancel(thread.getId(), pending.taskId(), stranger));
        assertThat(service.cancel(thread.getId(), pending.taskId(), owner).status()).isEqualTo("CANCELLED");
        assertThat(service.cancel(thread.getId(), pending.taskId(), owner).status()).isEqualTo("CANCELLED");
        assertThat(service.claim(pending.taskId())).isNull();
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_budget WHERE user_id=?", owner)).isZero();
    }

    @Test
    void processingCancellationRetainsCostEvidenceButLateResultCannotOverwriteNewTask() {
        var first = start();
        var old = service.claim(first.taskId());
        long reservation = service.readTask(first.taskId(), owner).chargedTokens();
        service.cancel(thread.getId(), first.taskId(), owner);
        assertThat(service.readTask(first.taskId(), owner).chargedTokens()).isEqualTo(reservation);
        var second = service.start(thread.getId(), owner, key(), true);
        var fresh = service.claim(second.taskId());
        service.settle(fresh, result("new", 20, 10), null);
        service.settle(old, result("late old", 40, 10), null);
        assertThat(service.read(thread.getId(), owner).summary()).isEqualTo("new");
        assertThat(service.readTask(first.taskId(), owner).status()).isEqualTo("CANCELLED");
        assertThat(sqlLong("SELECT charged_tokens FROM ai_summary_budget WHERE user_id=?", owner)).isEqualTo(80);
    }

    @Test
    void actualOwnerEditInvalidatesTaskAndKeepsOldResultOutOfContent() {
        String requestKey = key();
        var pending = service.start(thread.getId(), owner, requestKey, false);
        var claim = service.claim(pending.taskId());
        UpdateThreadRequest edit = new UpdateThreadRequest(); edit.setContent("编辑之后的新正文");
        threadService.update(owner, thread.getId(), edit);
        assertThat(service.read(thread.getId(), owner).status()).isEqualTo("IDLE");
        assertThat(service.readTask(pending.taskId(), owner).status()).isEqualTo("SUPERSEDED");
        status(409, () -> service.start(thread.getId(), owner, requestKey, false));
        service.settle(claim, result("旧摘要", 12, 5), null);
        assertThat(jdbc.queryForObject("SELECT summary FROM threads WHERE id=?", String.class, thread.getId())).isNull();
        assertThat(sqlLong("SELECT content_version FROM threads WHERE id=?", thread.getId())).isEqualTo(1);
        assertThat(service.readTask(pending.taskId(), owner).summary()).isEmpty();
    }

    @Test
    void duplicateConcurrentDeliveriesReserveExactlyOneAttempt() throws Exception {
        var pending = start();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return service.claim(pending.taskId()); });
            var b = pool.submit(() -> { start.await(); return service.claim(pending.taskId()); });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS) == null, b.get(10, TimeUnit.SECONDS) == null))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id=?", pending.taskId())).isEqualTo(1);
    }

    @Test
    void budgetExhaustionRejectsBeforeAttemptAndDoesNotChangeBalance() {
        var pending = start();
        jdbc.update("INSERT INTO ai_summary_budget(user_id,budget_day,charged_tokens) VALUES(?,?,?)", owner,
                LocalDate.now(ZoneOffset.UTC), 49_999);
        assertThat(service.claim(pending.taskId())).isNull();
        assertThat(service.readTask(pending.taskId(), owner).errorCode()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id=?", pending.taskId())).isZero();
        assertThat(sqlLong("SELECT charged_tokens FROM ai_summary_budget WHERE user_id=?", owner)).isEqualTo(49_999);
    }

    @Test
    void retriesAreBoundedAndEachUnknownAttemptRemainsCharged() {
        var pending = start();
        long oneReservation = 0;
        for (int i = 1; i <= 3; i++) {
            var claim = service.claim(pending.taskId());
            assertThat(claim.attempt()).isEqualTo(i);
            if (i == 1) oneReservation = service.readTask(pending.taskId(), owner).chargedTokens();
            service.settle(claim, null, AiSummaryFailureCode.TIMEOUT);
            jdbc.update("UPDATE ai_summary_task SET available_at=? WHERE task_id=?", Instant.now().minusSeconds(10), pending.taskId());
        }
        assertThat(service.claim(pending.taskId())).isNull();
        var failed = service.read(thread.getId(), owner);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("TIMEOUT");
        assertThat(failed.chargedTokens()).isEqualTo(oneReservation * 3);
        assertThat(failed.usageEstimated()).isTrue();
    }

    @Test
    void successfulRetryDoesNotLabelEarlierUnknownAttemptCostAsActualUsage() {
        var pending = start();
        var first = service.claim(pending.taskId());
        long reservation = service.readTask(pending.taskId(), owner).chargedTokens();
        service.settle(first, null, AiSummaryFailureCode.UPSTREAM);
        jdbc.update("UPDATE ai_summary_task SET available_at=? WHERE task_id=?", Instant.now().minusSeconds(1), pending.taskId());
        var second = service.claim(pending.taskId());
        service.settle(second, result("retry success", 10, 5), null);
        var completed = service.read(thread.getId(), owner);
        assertThat(completed.chargedTokens()).isEqualTo(reservation + 15);
        assertThat(completed.usageEstimated()).isTrue();
        assertThat(completed.inputTokens()).isEqualTo(10);
    }

    @Test
    void expiredLeaseCanBeTakenOverAndOldResultOnlyReconcilesItsOwnUsage() {
        var pending = start();
        var old = service.claim(pending.taskId());
        jdbc.update("UPDATE ai_summary_task SET lease_until=? WHERE task_id=?", Instant.now().minusSeconds(1), pending.taskId());
        var fresh = service.claim(pending.taskId());
        assertThat(fresh.attempt()).isEqualTo(2);
        service.settle(old, null, AiSummaryFailureCode.TRANSPORT);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_summary_attempt WHERE task_id=? AND attempt_number=1", String.class, pending.taskId()))
                .isEqualTo("EXPIRED");
        service.settle(old, result("stale", 9, 2), null);
        assertThat(service.read(thread.getId(), owner).status()).isEqualTo("PROCESSING");
        service.settle(fresh, result("fresh", 13, 3), null);
        assertThat(service.read(thread.getId(), owner).summary()).isEqualTo("fresh");
        assertThat(sqlLong("SELECT charged_tokens FROM ai_summary_budget WHERE user_id=?", owner)).isEqualTo(27);
    }

    @Test
    void revokedActorRecoveryClosesItsExpiredAttemptWithoutRefundingUnknownCost() {
        scopes.saveAndFlush(new CommunityModeratorScope(stranger, section.getId()));
        var pending = service.start(thread.getId(), stranger, key(), false);
        service.claim(pending.taskId());
        long reserved = service.readTask(pending.taskId(), owner).chargedTokens();
        scopes.deleteByUserId(stranger);
        jdbc.update("UPDATE ai_summary_task SET lease_until=? WHERE task_id=?", Instant.now().minusSeconds(1), pending.taskId());
        assertThat(service.claim(pending.taskId())).isNull();
        assertThat(service.readTask(pending.taskId(), owner).errorCode()).isEqualTo("PERMISSION_REVOKED");
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id=? AND status='FAILED' AND finished_at IS NOT NULL", pending.taskId())).isEqualTo(1);
        assertThat(service.readTask(pending.taskId(), owner).chargedTokens()).isEqualTo(reserved);
    }

    @Test
    void lateSuccessAfterExpiryBeforeTakeoverKeepsOriginalTaskRecoverable() {
        var pending = start();
        var old = service.claim(pending.taskId());
        jdbc.update("UPDATE ai_summary_task SET lease_until=? WHERE task_id=?", Instant.now().minusSeconds(1), pending.taskId());
        service.settle(old, result("expired result must not publish", 7, 3), null);
        assertThat(service.read(thread.getId(), owner).status()).isEqualTo("PENDING");
        assertThat(service.read(thread.getId(), owner).summary()).isEmpty();
        jdbc.update("UPDATE ai_summary_task SET available_at=? WHERE task_id=?", Instant.now().minusSeconds(1), pending.taskId());
        var fresh = service.claim(pending.taskId());
        assertThat(fresh.attempt()).isEqualTo(2);
        service.settle(fresh, result("fresh lease result", 10, 2), null);
        assertThat(service.read(thread.getId(), owner).summary()).isEqualTo("fresh lease result");
        assertThat(service.readTask(pending.taskId(), owner).chargedTokens()).isEqualTo(22);
    }

    @Test
    void cancellationAndContentChangeCloseAttemptEvenIfWorkerNeverReturns() {
        var cancelled = start();
        service.claim(cancelled.taskId());
        long before = service.readTask(cancelled.taskId(), owner).chargedTokens();
        service.cancel(thread.getId(), cancelled.taskId(), owner);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_summary_attempt WHERE task_id=?", String.class, cancelled.taskId()))
                .isEqualTo("CANCELLED");
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id=? AND finished_at IS NOT NULL", cancelled.taskId())).isEqualTo(1);
        assertThat(service.readTask(cancelled.taskId(), owner).chargedTokens()).isEqualTo(before);
        var obsolete = service.start(thread.getId(), owner, key(), true);
        service.claim(obsolete.taskId());
        UpdateThreadRequest edit = new UpdateThreadRequest(); edit.setContent("changed while worker vanished");
        threadService.update(owner, thread.getId(), edit);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_summary_attempt WHERE task_id=?", String.class, obsolete.taskId()))
                .isEqualTo("SUPERSEDED");
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_attempt WHERE task_id=? AND finished_at IS NOT NULL", obsolete.taskId())).isEqualTo(1);
    }

    @Test
    void interruptedConsumerRemainsRecoverableAndRestoresInterruptFlag() throws Exception {
        var pending = start();
        var started = new CountDownLatch(1);
        var future = new java.util.concurrent.CompletableFuture<AiSummaryResult>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        when(ai.requestSummary(anyString(), anyInt())).thenAnswer(ignored -> {
            started.countDown();
            return new AiSummaryCall(future, () -> future.completeExceptionally(new AiSummaryException(AiSummaryFailureCode.CANCELLED)));
        });
        var consumer = new java.lang.Thread(() -> {
            try { runner.execute(pending.taskId()); interrupted.set(java.lang.Thread.currentThread().isInterrupted()); }
            catch (Throwable failed) { error.set(failed); }
        });
        consumer.start();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            consumer.interrupt(); consumer.join(5000);
            assertThat(consumer.isAlive()).isFalse(); assertThat(error.get()).isNull();
            assertThat(interrupted.get()).isTrue();
            assertThat(service.read(thread.getId(), owner).status()).isEqualTo("PENDING");
            jdbc.update("UPDATE ai_summary_task SET available_at=? WHERE task_id=?", Instant.now().minusSeconds(1), pending.taskId());
            assertThat(service.claim(pending.taskId()).attempt()).isEqualTo(2);
        } finally { consumer.interrupt(); consumer.join(5000); }
    }

    @Test
    void settlementUsesReservationDayAcrossUtcMidnight() {
        var pending = start();
        var claim = service.claim(pending.taskId());
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        jdbc.update("UPDATE ai_summary_attempt SET budget_day=? WHERE task_id=?", yesterday, pending.taskId());
        jdbc.update("UPDATE ai_summary_budget SET budget_day=? WHERE user_id=?", yesterday, owner);
        service.settle(claim, result("across midnight", 7, 4), null);
        assertThat(jdbc.queryForObject("SELECT budget_day FROM ai_summary_budget WHERE user_id=?", LocalDate.class, owner)).isEqualTo(yesterday);
        assertThat(sqlLong("SELECT charged_tokens FROM ai_summary_budget WHERE user_id=?", owner)).isEqualTo(11);
    }

    @Test
    void hiddenParentDeniesCurrentAndHistoricalReadsAndRejectsLateResult() {
        var pending = start();
        var claim = service.claim(pending.taskId());
        threadService.hideForModeration(thread.getId());
        status(404, () -> service.read(thread.getId(), owner));
        status(404, () -> service.readTask(pending.taskId(), owner));
        service.settle(claim, result("hidden old", 10, 2), null);
        threadService.restoreForModeration(thread.getId());
        assertThat(service.read(thread.getId(), owner).status()).isEqualTo("IDLE");
        assertThat(service.readTask(pending.taskId(), owner).summary()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT summary FROM threads WHERE id=?", String.class, thread.getId())).isNull();
    }

    @Test
    void longInputIsNotTruncatedAndOversizedInputDoesNotCreateTask() {
        Thread longThread = thread("长".repeat(9000));
        var pending = service.start(longThread.getId(), owner, key(), false);
        assertThat(service.claim(pending.taskId()).input()).hasSize(9000);
        Thread oversized = thread("x".repeat(12001));
        status(400, () -> service.start(oversized.getId(), owner, key(), false));
        assertThat(sqlLong("SELECT COUNT(*) FROM ai_summary_task WHERE thread_id=?", oversized.getId())).isZero();
    }

    @Test
    void recoveryUsesBoundedCursorAndIncludesOnlyEligibleIntentOrExpiredLease() {
        var pending = start();
        var active = service.start(thread("active").getId(), owner, key(), false);
        service.claim(active.taskId());
        Instant now = Instant.now();
        jdbc.update("UPDATE ai_summary_task SET updated_at=? WHERE task_id=?", now.minusSeconds(60), pending.taskId());
        assertThat(taskRows.recoverable("", now, now.minusSeconds(30), PageRequest.of(0, 20)))
                .contains(pending.taskId()).doesNotContain(active.taskId());
        assertThat(taskRows.recoverable(pending.taskId(), now, now.minusSeconds(30), PageRequest.of(0, 20)))
                .doesNotContain(pending.taskId());
        jdbc.update("UPDATE ai_summary_task SET lease_until=? WHERE task_id=?", now.minusSeconds(1), active.taskId());
        assertThat(taskRows.recoverable("", now, now.minusSeconds(30), PageRequest.of(0, 20))).contains(active.taskId());
    }

    private SummaryView start() { return service.start(thread.getId(), owner, key(), false); }
    private Thread thread(String body) {
        Thread row = new Thread(); row.setSectionId(section.getId()); row.setAuthorId(owner);
        row.setTitle("summary source"); row.setContentMd(body); return threads.saveAndFlush(row);
    }
    private Long user() {
        String id = key(); User row = new User(); row.setUsername("s-" + id); row.setEmail(id + "@test.invalid");
        row.setPhone(id.substring(0, 20)); row.setPasswordHash("not-a-real-secret"); return users.saveAndFlush(row).getId();
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private long sqlLong(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static AiSummaryResult result(String text, long input, long output) {
        return new AiSummaryResult(text, "controlled-test", "controlled-model", "actual-model", "req-id", "response-id", input, output, 12);
    }
    private static void status(int code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(code));
    }
}
