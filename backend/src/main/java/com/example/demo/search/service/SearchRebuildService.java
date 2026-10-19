package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class SearchRebuildService {
    private final ContentWriteGateService control;
    private final ThreadSearchProjectionService owner;
    private final ContentSearchIndex index;

    public SearchRebuildService(ContentWriteGateService control, ThreadSearchProjectionService owner,
                                ContentSearchIndex index) {
        this.control = control;
        this.owner = owner;
        this.index = index;
    }

    /** All content-event writers participate in the persistent gate, across instances. */
    public RebuildResult rebuild() {
        String token = UUID.randomUUID().toString();
        String target = ContentSearchIndex.PREFIX + UUID.randomUUID().toString().replace("-", "");
        control.begin(token);
        try {
            index.create(target);
            long afterId = 0;
            long count = 0;
            while (true) {
                control.heartbeat(token);
                var batch = owner.scanAfter(afterId, 20);
                if (batch.isEmpty()) {
                    break;
                }
                for (ThreadSearchDocument document : batch) {
                    if (document.id() <= afterId) {
                        throw new IllegalStateException("Non-monotonic owner cursor");
                    }
                    index.put(target, document);
                    afterId = document.id();
                    count++;
                }
            }
            index.refresh(target);
            if (index.count(target) != count) {
                throw new IllegalStateException("Rebuild count mismatch");
            }
            // Compare every projection, including tombstones, before exposing the new alias.
            afterId = 0;
            long verified = 0;
            while (true) {
                control.heartbeat(token);
                var batch = owner.scanAfter(afterId, 20);
                if (batch.isEmpty()) {
                    break;
                }
                for (ThreadSearchDocument document : batch) {
                    if (!index.read(target, document.id()).filter(document::equals).isPresent()) {
                        throw new IllegalStateException("Rebuild document mismatch");
                    }
                    afterId = document.id();
                    verified++;
                }
            }
            if (verified != count) {
                throw new IllegalStateException("Rebuild verification count mismatch");
            }
            finishOrReconcile(token, target);
            return new RebuildResult(target, count, verified);
        } finally {
            // An expired/replaced token cannot release another instance's maintenance gate.
            control.abort(token);
        }
    }

    private void finishOrReconcile(String token, String target) {
        try {
            control.finish(token, target, () -> index.switchAlias(target));
        } catch (RuntimeException uncertain) {
            try {
                // A lost HTTP/commit response does not prove the external alias stayed unchanged.
                // Re-read it while the same maintenance owner still holds the gate; never switch twice.
                control.finish(token, target, () -> {
                    if (!index.activeIndex().filter(target::equals).isPresent()) {
                        throw new IllegalStateException("Search alias switch could not be reconciled");
                    }
                });
            } catch (RuntimeException unresolved) {
                control.invalidateForOwner(token);
                // The first transaction may have committed despite a lost database response.
                var state = control.snapshot();
                if (state.ready() && target.equals(state.activeIndex())
                        && index.activeIndex().filter(target::equals).isPresent()) {
                    return;
                }
                throw new IllegalStateException("Search rebuild requires reconciliation", unresolved);
            }
        }
    }

    public record RebuildResult(String index, long documents, long verified) { }
}
