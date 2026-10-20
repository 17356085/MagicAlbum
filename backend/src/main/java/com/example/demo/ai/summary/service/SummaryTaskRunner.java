package com.example.demo.ai.summary.service;

import com.example.demo.ai.service.AiService;
import com.example.demo.ai.service.AiSummaryCall;
import com.example.demo.ai.service.AiSummaryException;
import com.example.demo.ai.service.AiSummaryFailureCode;
import com.example.demo.ai.service.AiSummaryResult;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/** The blocking wait occupies only a bounded queue consumer, never a SQL transaction. */
@Service
public class SummaryTaskRunner {
    private final SummaryTaskService tasks;
    private final AiService ai;
    private final SummaryCancellationRegistry cancellation;

    public SummaryTaskRunner(SummaryTaskService tasks, AiService ai, SummaryCancellationRegistry cancellation) {
        this.tasks = tasks; this.ai = ai; this.cancellation = cancellation;
    }

    public void execute(String taskId) {
        SummaryTaskService.Claim claim = tasks.claim(taskId);
        if (claim == null) return;
        AiSummaryCall call = null;
        AiSummaryResult result = null;
        AiSummaryFailureCode failure = null;
        boolean interrupted = false;
        try {
            if (!tasks.owns(claim)) return;
            call = ai.requestSummary(claim.input(), claim.outputTokens());
            cancellation.register(claim.taskId(), claim.owner(), call);
            // A cancellation can commit between claim and transport registration.
            if (!tasks.owns(claim)) call.cancel();
            result = call.completion().get(125, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            interrupted = true;
            failure = AiSummaryFailureCode.TRANSPORT;
        } catch (TimeoutException timeout) {
            failure = AiSummaryFailureCode.TIMEOUT;
        } catch (CancellationException cancelled) {
            failure = AiSummaryFailureCode.TRANSPORT;
        } catch (ExecutionException error) {
            failure = error.getCause() instanceof AiSummaryException safe ? recoverableCancellation(safe.code()) : AiSummaryFailureCode.TRANSPORT;
        } catch (AiSummaryException safe) {
            failure = recoverableCancellation(safe.code());
        } catch (RuntimeException error) {
            failure = AiSummaryFailureCode.TRANSPORT;
        } finally {
            if (call != null) {
                if (result == null) call.cancel();
                cancellation.remove(claim.taskId(), claim.owner(), call);
            }
            try { tasks.settle(claim, result, failure); }
            finally { if (interrupted) java.lang.Thread.currentThread().interrupt(); }
        }
    }

    private static AiSummaryFailureCode recoverableCancellation(AiSummaryFailureCode code) {
        // Explicit cancellation already owns a persisted terminal task. Shutdown/transport
        // cancellation without that state must remain recoverable after restart.
        return code == AiSummaryFailureCode.CANCELLED ? AiSummaryFailureCode.TRANSPORT : code;
    }
}
