package com.example.demo.ai.summary;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryCall;
import com.example.demo.ai.service.AiSummaryException;
import com.example.demo.ai.service.AiSummaryFailureCode;
import com.example.demo.ai.service.AiSummaryResult;
import com.example.demo.ai.summary.service.SummaryCancellationRegistry;
import com.example.demo.ai.summary.service.SummaryTaskRunner;
import com.example.demo.ai.summary.service.SummaryTaskService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SummaryTaskRunnerTest {
    @Test
    void cancellationBetweenClaimAndRegistrationClosesTransportAndCannotPublish() {
        var tasks = mock(SummaryTaskService.class);
        var ai = mock(AiService.class);
        var registry = new SummaryCancellationRegistry();
        var claim = new SummaryTaskService.Claim("task", 1L, 2L, 0, 1, "owner", "source", 512);
        var future = new CompletableFuture<AiSummaryResult>();
        var cancelled = new AtomicInteger();
        var call = new AiSummaryCall(future, () -> {
            cancelled.incrementAndGet();
            future.completeExceptionally(new AiSummaryException(AiSummaryFailureCode.CANCELLED));
        });
        when(tasks.claim("task")).thenReturn(claim);
        when(tasks.owns(claim)).thenReturn(true, false);
        when(ai.requestSummary("source", 512)).thenReturn(call);
        new SummaryTaskRunner(tasks, ai, registry).execute("task");
        assertThat(cancelled.get()).isEqualTo(1);
        verify(tasks).settle(claim, null, AiSummaryFailureCode.TRANSPORT);
    }

    @Test
    void oldLeaseRegistrationDoesNotCancelAnotherOwnerAndCancelTargetsAllLocalAttempts() {
        var registry = new SummaryCancellationRegistry();
        var oldCancel = new AtomicInteger();
        var freshCancel = new AtomicInteger();
        var old = new AiSummaryCall(new CompletableFuture<>(), oldCancel::incrementAndGet);
        var fresh = new AiSummaryCall(new CompletableFuture<>(), freshCancel::incrementAndGet);
        registry.register("task", "fresh-owner", fresh);
        registry.register("task", "old-owner", old);
        assertThat(freshCancel.get()).isZero();
        registry.remove("task", "old-owner", old);
        registry.cancel("task");
        assertThat(freshCancel.get()).isEqualTo(1);
        assertThat(oldCancel.get()).isZero();
        registry.close();
    }

    @Test
    void successfulTransportIsSettledOnceWithoutUnnecessaryCancellation() {
        var tasks = mock(SummaryTaskService.class);
        var ai = mock(AiService.class);
        var claim = new SummaryTaskService.Claim("task", 1L, 2L, 0, 1, "owner", "source", 512);
        var result = new AiSummaryResult("summary", "test", "model", null, null, null, 10L, 3L, 1);
        var cancelled = new AtomicInteger();
        when(tasks.claim("task")).thenReturn(claim);
        when(tasks.owns(claim)).thenReturn(true);
        when(ai.requestSummary("source", 512)).thenReturn(new AiSummaryCall(CompletableFuture.completedFuture(result), cancelled::incrementAndGet));
        new SummaryTaskRunner(tasks, ai, new SummaryCancellationRegistry()).execute("task");
        verify(tasks).settle(claim, result, null);
        assertThat(cancelled.get()).isZero();
    }
}
