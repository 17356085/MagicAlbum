package com.example.demo.ai.summary.service;

import com.example.demo.ai.service.AiSummaryCall;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Local cancellation is best effort; persisted task ownership is the final write fence. */
@Component
public class SummaryCancellationRegistry {
    private record Key(String taskId, String owner) { }
    private final ConcurrentHashMap<Key, AiSummaryCall> calls = new ConcurrentHashMap<>();

    public void register(String taskId, String owner, AiSummaryCall call) {
        calls.compute(new Key(taskId, owner), (id, previous) -> {
            if (previous != null) previous.cancel();
            return call;
        });
    }

    public void remove(String taskId, String owner, AiSummaryCall call) { calls.remove(new Key(taskId, owner), call); }
    public void cancel(String taskId) {
        calls.forEach((key, call) -> {
            if (key.taskId().equals(taskId) && calls.remove(key, call)) call.cancel();
        });
    }

    @PreDestroy
    public void close() { calls.forEach((id, call) -> call.cancel()); calls.clear(); }
}
