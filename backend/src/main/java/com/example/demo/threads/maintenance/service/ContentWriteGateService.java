package com.example.demo.threads.maintenance.service;

import com.example.demo.threads.maintenance.entity.ContentSearchControl;
import com.example.demo.threads.maintenance.repo.ContentSearchControlRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Persistent cross-instance pause gate for all content-event writers. */
@Service
public class ContentWriteGateService {
    private static final long CONTROL_ID = 1L;
    private static final Duration LEASE = Duration.ofSeconds(300);

    private final ContentSearchControlRepository repository;
    private final Clock clock;

    @Autowired
    public ContentWriteGateService(ContentSearchControlRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ContentWriteGateService(ContentSearchControlRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initialize() {
        if (repository.findById(CONTROL_ID).isPresent()) {
            return;
        }
        ContentSearchControl control = new ContentSearchControl();
        control.setId(CONTROL_ID);
        control.setReady(false);
        repository.save(control);
    }

    /**
     * Acquires a shared row lock and retains it for the caller's transaction.
     * Every content write must call this method before committing its owner row and event.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enter() {
        ContentSearchControl control = requireControl(repository.findByIdForRead(CONTROL_ID));
        if (isPaused(control, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Content writes are paused for search rebuild");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(String owner) {
        requireOwner(owner);
        ContentSearchControl control = requireControl(repository.findByIdForUpdate(CONTROL_ID));
        Instant now = clock.instant();
        if (hasValidLease(control, now)) {
            throw conflict("A search rebuild is already active");
        }
        control.setPauseOwner(owner);
        control.setPauseUntil(now.plus(LEASE));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(String owner) {
        requireOwner(owner);
        ContentSearchControl control = requireControl(repository.findByIdForUpdate(CONTROL_ID));
        assertActiveOwner(control, owner);
        control.setPauseUntil(clock.instant().plus(LEASE));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String owner, String newIndex, Runnable aliasSwitch) {
        requireOwner(owner);
        if (newIndex == null || newIndex.isBlank() || newIndex.length() > 128) {
            throw new IllegalArgumentException("newIndex must be between 1 and 128 characters");
        }
        ContentSearchControl control = requireControl(repository.findByIdForUpdate(CONTROL_ID));
        assertActiveOwner(control, owner);
        runAliasSwitch(aliasSwitch);
        control.setReady(true);
        control.setActiveIndex(newIndex);
        control.setPauseOwner(null);
        control.setPauseUntil(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidateIfActive(String expectedIndex) {
        ContentSearchControl control = requireControl(repository.findByIdForUpdate(CONTROL_ID));
        if (Objects.equals(expectedIndex, control.getActiveIndex())) {
            control.setReady(false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidateForOwner(String owner) {
        requireOwner(owner);
        ContentSearchControl control = requireControl(repository.findByIdForUpdate(CONTROL_ID));
        if (owner.equals(control.getPauseOwner())) {
            control.setReady(false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String owner) {
        if (owner == null || owner.isBlank()) {
            return;
        }
        ContentSearchControl control = requireControl(repository.findByIdForUpdate(CONTROL_ID));
        if (owner.equals(control.getPauseOwner())) {
            control.setPauseOwner(null);
            control.setPauseUntil(null);
        }
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        ContentSearchControl control = requireControl(repository.findByIdForRead(CONTROL_ID));
        return new Snapshot(
                control.getActiveIndex(),
                control.isReady(),
                hasValidLease(control, clock.instant()),
                control.getPauseUntil());
    }

    private void assertActiveOwner(ContentSearchControl control, String owner) {
        Instant now = clock.instant();
        if (!owner.equals(control.getPauseOwner())
                || control.getPauseUntil() == null
                || !control.getPauseUntil().isAfter(now)) {
            throw conflict("Search rebuild ownership is no longer valid");
        }
    }

    private boolean isPaused(ContentSearchControl control, Instant now) {
        return control.getPauseOwner() != null
                && (control.getPauseUntil() == null || control.getPauseUntil().isAfter(now));
    }

    private boolean hasValidLease(ContentSearchControl control, Instant now) {
        return control.getPauseOwner() != null
                && control.getPauseUntil() != null
                && control.getPauseUntil().isAfter(now);
    }

    private ContentSearchControl requireControl(Optional<ContentSearchControl> control) {
        return control.orElseThrow(() -> new IllegalStateException(
                "content_search_control singleton row is missing"));
    }

    private void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 36) {
            throw new IllegalArgumentException("owner must be between 1 and 36 characters");
        }
    }

    private void runAliasSwitch(Runnable aliasSwitch) {
        if (aliasSwitch == null) {
            throw new IllegalArgumentException("aliasSwitch is required");
        }
        aliasSwitch.run();
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Snapshot(String activeIndex, boolean ready, boolean paused, Instant pauseUntil) {
    }
}
