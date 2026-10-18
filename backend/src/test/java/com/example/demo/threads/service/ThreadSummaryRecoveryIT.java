package com.example.demo.threads.service;

import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.repo.ThreadSummaryRecoveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:summary_recovery;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.jpa.show-sql=false"
})
@DisabledInAotMode
@Transactional
class ThreadSummaryRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @Autowired ThreadRepository threads;
    @Autowired ThreadSummaryRecoveryRepository recovery;

    @Test
    void onlyOldVisiblePendingIntentionsAreSelectedInBoundedKeyOrder() {
        Long first = save("NORMAL", "PENDING", NOW.minusSeconds(120));
        Long second = save("NORMAL", "PENDING", NOW.minusSeconds(100));
        save("NORMAL", "PENDING", NOW);
        save("HIDDEN", "PENDING", NOW.minusSeconds(120));
        save("DELETED", "PENDING", NOW.minusSeconds(120));
        Long active = save("NORMAL", "PROCESSING", NOW.minusSeconds(120));
        Thread activeThread = threads.findById(active).orElseThrow();
        activeThread.setSummaryLeaseUntil(NOW.plusSeconds(180));
        threads.saveAndFlush(activeThread);
        save("NORMAL", "COMPLETED", NOW.minusSeconds(120));
        save("NORMAL", "FAILED", NOW.minusSeconds(120));

        List<Long> firstPage = recovery.findPendingIds(0, NOW.minusSeconds(60), NOW, PageRequest.of(0, 1));
        assertThat(firstPage).containsExactly(first);
        assertThat(recovery.findPendingIds(first, NOW.minusSeconds(60), NOW, PageRequest.of(0, 20)))
                .containsExactly(second);
    }

    @Test
    void duplicateDeliveriesClaimOneVisibleTaskAndCannotClaimHiddenTasks() {
        Long pending = save("NORMAL", "PENDING", NOW.minusSeconds(120));
        Long hidden = save("HIDDEN", "PENDING", NOW.minusSeconds(120));
        assertThat(threads.claimSummaryProcessingIfNormal(pending, "owner", NOW, NOW.plusSeconds(180))).isEqualTo(1);
        assertThat(threads.claimSummaryProcessingIfNormal(pending, "owner", NOW, NOW.plusSeconds(180))).isZero();
        assertThat(threads.claimSummaryProcessingIfNormal(hidden, "owner", NOW, NOW.plusSeconds(180))).isZero();
        assertThat(threads.findById(pending).orElseThrow().getSummaryStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void completingWorkCannotRestoreVisibilityOrWriteSummaryAfterDeletion() {
        Long deleted = save("DELETED", "PROCESSING", NOW.minusSeconds(120));
        assertThat(threads.completeSummaryIfNormal(deleted, "must not persist", "owner", NOW, NOW)).isZero();
        Thread persisted = threads.findById(deleted).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("DELETED");
        assertThat(persisted.getSummary()).isNull();
    }

    @Test
    void expiredLeaseCanBeTakenOverButOldWorkerCannotCompleteOrFailNewWork() {
        Long id = save("NORMAL", "PENDING", NOW.minusSeconds(120));
        assertThat(threads.claimSummaryProcessingIfNormal(id, "old", NOW, NOW.plusSeconds(180))).isEqualTo(1);
        assertThat(threads.claimSummaryProcessingIfNormal(id, "new", NOW.plusSeconds(181), NOW.plusSeconds(361))).isEqualTo(1);
        assertThat(threads.completeSummaryIfNormal(id, "stale", "old", NOW.plusSeconds(182), NOW)).isZero();
        assertThat(threads.markSummaryFailedIfProcessingAndNormal(id, "old", NOW.plusSeconds(182))).isZero();
        assertThat(threads.completeSummaryIfNormal(id, "fresh", "new", NOW.plusSeconds(182), NOW)).isEqualTo(1);
        assertThat(threads.findById(id).orElseThrow().getSummary()).isEqualTo("fresh");
    }

    @Test
    void expiredLeaseCannotFailBeforeTakeoverButNewOwnerCanClaim() {
        Long id = save("NORMAL", "PENDING", NOW.minusSeconds(120));
        assertThat(threads.claimSummaryProcessingIfNormal(id, "old", NOW, NOW.plusSeconds(180))).isEqualTo(1);

        assertThat(threads.markSummaryFailedIfProcessingAndNormal(id, "old", NOW.plusSeconds(181))).isZero();
        assertThat(threads.findById(id).orElseThrow().getSummaryStatus()).isEqualTo("PROCESSING");
        assertThat(threads.claimSummaryProcessingIfNormal(id, "new", NOW.plusSeconds(181), NOW.plusSeconds(361)))
                .isEqualTo(1);
    }

    @Test
    void unexpiredOwnerCanMarkSummaryFailed() {
        Long id = save("NORMAL", "PENDING", NOW.minusSeconds(120));
        assertThat(threads.claimSummaryProcessingIfNormal(id, "owner", NOW, NOW.plusSeconds(180))).isEqualTo(1);

        assertThat(threads.markSummaryFailedIfProcessingAndNormal(id, "owner", NOW.plusSeconds(1))).isEqualTo(1);
        assertThat(threads.findById(id).orElseThrow().getSummaryStatus()).isEqualTo("FAILED");
    }

    @Test
    void crashedTasksAreRecoveredAndEventuallyReachBoundedTerminalState() {
        Long id = save("NORMAL", "PENDING", NOW.minusSeconds(120));
        for (int i = 0; i < 5; i++) {
            Instant now = NOW.plusSeconds(i * 181L);
            assertThat(threads.claimSummaryProcessingIfNormal(id, "owner-" + i, now, now.plusSeconds(180))).isEqualTo(1);
        }
        Instant later = NOW.plusSeconds(1000);
        assertThat(recovery.findPendingIds(0, NOW, later, PageRequest.of(0, 20))).contains(id);
        assertThat(threads.exhaustSummaryLease(id, later)).isEqualTo(1);
        assertThat(threads.findById(id).orElseThrow().getSummaryStatus()).isEqualTo("FAILED");
        assertThat(threads.updateSummaryStatusIfNormal(id, "PENDING")).isEqualTo(1);
        assertThat(threads.claimSummaryProcessingIfNormal(id, "retry", later, later.plusSeconds(180))).isEqualTo(1);
    }

    @Test
    void expiredOrEditedSourceCannotAcceptAResult() {
        Long id = save("NORMAL", "PENDING", NOW.minusSeconds(120));
        threads.claimSummaryProcessingIfNormal(id, "owner", NOW, NOW.plusSeconds(180));
        assertThat(threads.completeSummaryIfNormal(id, "stale", "owner", NOW.plusSeconds(181), NOW)).isZero();
        assertThat(threads.completeSummaryIfNormal(id, "wrong source", "owner", NOW.plusSeconds(1), NOW.minusSeconds(1))).isZero();
    }

    private Long save(String status, String summaryStatus, Instant createdAt) {
        Thread thread = new Thread();
        thread.setSectionId(1L);
        thread.setAuthorId(1L);
        thread.setTitle("recovery fixture");
        thread.setContentMd("body");
        thread.setStatus(status);
        thread.setSummaryStatus(summaryStatus);
        thread.setCreatedAt(createdAt);
        thread.setUpdatedAt(NOW);
        return threads.saveAndFlush(thread).getId();
    }
}
