package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class SearchRebuildService {
    private static final Pattern MANAGED_PHYSICAL_INDEX = Pattern.compile(
            "^" + ContentSearchIndex.PREFIX + "[0-9a-f]{32}$");

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
        return rebuildInto(token, target, true);
    }

    /**
     * Replays the authoritative owner projection into a retained physical index
     * before making that index the active alias target.
     */
    public RebuildResult rollback(String target) {
        requireManagedPhysicalIndex(target);
        return rebuildInto(UUID.randomUUID().toString(), target, false);
    }

    private RebuildResult rebuildInto(String token, String target, boolean createTarget) {
        control.begin(token);
        try {
            if (createTarget) {
                index.create(target);
            } else {
                if (index.activeIndex().filter(target::equals).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Search rollback target is already active");
                }
                // A rollback may only reuse an existing retained index. The
                // count call verifies it before projection starts, so put() does
                // not implicitly create a target that was missing at preflight.
                index.count(target);
            }
            long count = project(token, target);
            long verified = verify(token, List.of(target));
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

    public RebuildResult preparePilotIndex(String target) {
        requireManagedPhysicalIndex(target);
        String token = UUID.randomUUID().toString();
        control.begin(token);
        try {
            index.create(target);
            long count = project(token, target);
            long verified = verify(token, List.of(target));
            if (verified != count) {
                throw new IllegalStateException("Pilot index preparation count mismatch");
            }
            return new RebuildResult(target, count, verified);
        } finally {
            control.abort(token);
        }
    }

    /** Both independent consumers must already have applied the owner state before a switch. */
    public RebuildResult activatePilotIndex(String target, String peer) {
        requireManagedPhysicalIndex(target);
        requireManagedPhysicalIndex(peer);
        if (target.equals(peer)) {
            throw new IllegalArgumentException("Parallel indexes must be distinct");
        }
        String token = UUID.randomUUID().toString();
        control.begin(token);
        try {
            long verified = verify(token, List.of(target, peer));
            finishOrReconcile(token, target);
            return new RebuildResult(target, verified, verified);
        } finally {
            control.abort(token);
        }
    }

    private long project(String token, String target) {
        long afterId = 0;
        long count = 0;
        while (true) {
            control.heartbeat(token);
            var batch = owner.scanAfter(afterId, 20);
            if (batch.isEmpty()) {
                return count;
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
    }

    private long verify(String token, List<String> targets) {
        for (String target : targets) index.refresh(target);
        var counts = targets.stream().map(index::count).toList();
        long afterId = 0;
        long verified = 0;
        while (true) {
            control.heartbeat(token);
            var batch = owner.scanAfter(afterId, 20);
            if (batch.isEmpty()) break;
            for (ThreadSearchDocument document : batch) {
                if (document.id() <= afterId) {
                    throw new IllegalStateException("Non-monotonic owner cursor");
                }
                for (String target : targets) {
                    if (index.read(target, document.id()).filter(document::equals).isEmpty()) {
                        throw new IllegalStateException("Rebuild document mismatch");
                    }
                }
                afterId = document.id();
                verified++;
            }
        }
        for (long count : counts) {
            if (verified != count) {
                throw new IllegalStateException("Rebuild count mismatch");
            }
        }
        return verified;
    }

    static void requireManagedPhysicalIndex(String target) {
        if (target == null || !MANAGED_PHYSICAL_INDEX.matcher(target).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search rollback target must be a managed physical index");
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
