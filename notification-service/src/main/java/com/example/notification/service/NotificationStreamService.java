package com.example.notification.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

@Service
public class NotificationStreamService {
    private final NotificationEventLog events;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;
    private final ExecutorService senders;
    private final LongFunction<SseEmitter> emitterFactory;
    private final long pollDelayMillis;
    private final AtomicBoolean stopping = new AtomicBoolean();

    @Autowired
    public NotificationStreamService(NotificationEventLog events) {
        this(events, new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "notification-stream-timer");
            thread.setDaemon(true);
            return thread;
        }), Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("notification-stream-send-", 0).factory()),
                SseEmitter::new, 2000);
    }

    NotificationStreamService(NotificationEventLog events, ScheduledThreadPoolExecutor scheduler,
                              ExecutorService senders, LongFunction<SseEmitter> emitterFactory, long pollDelayMillis) {
        this.events = events;
        this.scheduler = scheduler;
        this.senders = senders;
        this.emitterFactory = emitterFactory;
        this.pollDelayMillis = pollDelayMillis;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public SseEmitter open(Long owner, Instant tokenExpiry, Long cursor) {
        if (stopping.get()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        Instant now = Instant.now();
        Instant deadline = tokenExpiry.isBefore(now.plusSeconds(55)) ? tokenExpiry : now.plusSeconds(55);
        long timeout = Duration.between(now, deadline).toMillis();
        if (timeout <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        // A slow database read must not hold the admission lock for every owner.
        long latest = events.current(owner);
        long initial = cursor == null ? latest : cursor;
        if (initial < 0 || initial > latest) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知游标无效");
        Session session;
        synchronized (sessions) {
            if (stopping.get()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            if (sessions.size() >= 128 || sessions.values().stream().filter(value -> value.owner.equals(owner)).count() >= 2) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "通知连接过多");
            }
            session = new Session(owner, initial, deadline, emitterFactory.apply(timeout), cursor == null);
            sessions.put(session.id, session);
        }
        session.emitter.onCompletion(session::close);
        session.emitter.onTimeout(session::close);
        session.emitter.onError(error -> session.close());
        try {
            // Timer threads only dispatch. Socket writes, completion flushes and
            // database polling run outside them, with one poll in flight per owner session.
            session.future = scheduler.scheduleWithFixedDelay(session::dispatchPoll, 100, pollDelayMillis, TimeUnit.MILLISECONDS);
            long remaining = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            session.expiryFuture = scheduler.schedule(session::close, remaining, TimeUnit.MILLISECONDS);
            if (session.closed.get()) session.cancelTimers();
        } catch (RejectedExecutionException failed) { session.close(); }
        return session.emitter;
    }

    @PreDestroy
    public void close() {
        List<Session> closing;
        synchronized (sessions) {
            if (!stopping.compareAndSet(false, true)) return;
            closing = List.copyOf(sessions.values());
        }
        closing.forEach(Session::close);
        scheduler.shutdownNow();
        // Completion can wait on a blocked emitter's write lock. Do not join
        // these daemon virtual threads during application shutdown.
        synchronized (senders) {
            senders.shutdown();
        }
    }

    private final class Session {
        final String id = UUID.randomUUID().toString();
        final Long owner;
        final Instant deadline;
        final SseEmitter emitter;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean polling = new AtomicBoolean();
        final AtomicBoolean completed = new AtomicBoolean();
        volatile ScheduledFuture<?> future;
        volatile ScheduledFuture<?> expiryFuture;
        long cursor;
        boolean resetPending;

        Session(Long owner, long cursor, Instant deadline, SseEmitter emitter, boolean resetPending) {
            this.owner = owner;
            this.cursor = cursor;
            this.deadline = deadline;
            this.emitter = emitter;
            this.resetPending = resetPending;
        }

        void dispatchPoll() {
            if (closed.get() || !polling.compareAndSet(false, true)) return;
            try {
                senders.execute(() -> {
                    try { poll(); }
                    finally {
                        polling.set(false);
                        releaseIfFinished();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                polling.set(false);
                close();
                releaseIfFinished();
            }
        }

        void poll() {
            if (closed.get()) return;
            if (!Instant.now().isBefore(deadline)) { close(); return; }
            try {
                if (resetPending) {
                    emitter.send(SseEmitter.event().name("reset").id(Long.toString(cursor)).data(Map.of("kind", "reset")));
                    resetPending = false;
                    if (closed.get() || !Instant.now().isBefore(deadline)) { close(); return; }
                }
                var batch = events.after(owner, cursor, 50);
                for (var event : batch) {
                    if (closed.get() || !Instant.now().isBefore(deadline)) { close(); return; }
                    emitter.send(SseEmitter.event().name("notification").id(event.cursor()).data(event));
                    cursor = Long.parseLong(event.cursor());
                }
                if (batch.isEmpty() && !closed.get() && Instant.now().isBefore(deadline)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                }
            } catch (Exception failure) { close(); }
        }

        void close() {
            // Serialize cleanup submission with executor shutdown, never with
            // the emitter's potentially blocking write/complete operations.
            synchronized (senders) {
                if (!closed.compareAndSet(false, true)) return;
                cancelTimers();
                senders.execute(() -> {
                    try { emitter.complete(); }
                    finally {
                        completed.set(true);
                        releaseIfFinished();
                    }
                });
            }
        }

        void cancelTimers() {
            if (future != null) future.cancel(false);
            if (expiryFuture != null) expiryFuture.cancel(false);
        }

        void releaseIfFinished() {
            // Closing connections keep their slots until both tasks finish.
            // Otherwise repeated reconnects could accumulate blocked writers.
            if (closed.get() && completed.get() && !polling.get()) sessions.remove(id, this);
        }
    }
}
