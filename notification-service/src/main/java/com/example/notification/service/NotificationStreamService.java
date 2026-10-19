package com.example.notification.service;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NotificationStreamService {
    private final NotificationEventLog events;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "notification-stream");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationStreamService(NotificationEventLog events) {
        this.events = events;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public SseEmitter open(Long owner, Instant tokenExpiry, Long cursor) {
        Instant deadline = tokenExpiry.isBefore(Instant.now().plusSeconds(55)) ? tokenExpiry : Instant.now().plusSeconds(55);
        long timeout = Duration.between(Instant.now(), deadline).toMillis();
        if (timeout <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Session session;
        synchronized (sessions) {
            if (sessions.size() >= 128 || sessions.values().stream().filter(value -> value.owner.equals(owner)).count() >= 2) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "通知连接过多");
            }
            long initial = cursor == null ? events.current(owner) : cursor;
            if (initial < 0 || initial > events.current(owner)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知游标无效");
            session = new Session(owner, initial, deadline, new SseEmitter(timeout));
            sessions.put(session.id, session);
        }
        session.emitter.onCompletion(session::close);
        session.emitter.onTimeout(session::close);
        session.emitter.onError(error -> session.close());
        try {
            if (cursor == null) session.emitter.send(SseEmitter.event().name("reset").id(Long.toString(session.cursor))
                    .data(Map.of("kind", "reset")));
            session.future = scheduler.scheduleWithFixedDelay(session::poll, 100, 2000, TimeUnit.MILLISECONDS);
            if (session.closed.get()) session.future.cancel(false);
        } catch (Exception failed) { session.close(); }
        return session.emitter;
    }

    @PreDestroy
    public void close() {
        sessions.values().forEach(Session::close);
        scheduler.shutdownNow();
    }

    private final class Session {
        final String id = UUID.randomUUID().toString();
        final Long owner;
        final Instant deadline;
        final SseEmitter emitter;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile ScheduledFuture<?> future;
        long cursor;

        Session(Long owner, long cursor, Instant deadline, SseEmitter emitter) {
            this.owner = owner;
            this.cursor = cursor;
            this.deadline = deadline;
            this.emitter = emitter;
        }

        void poll() {
            if (closed.get()) return;
            if (!Instant.now().isBefore(deadline)) { close(); return; }
            try {
                var batch = events.after(owner, cursor, 50);
                for (var event : batch) {
                    if (closed.get() || !Instant.now().isBefore(deadline)) { close(); return; }
                    emitter.send(SseEmitter.event().name("notification").id(event.cursor()).data(event));
                    cursor = Long.parseLong(event.cursor());
                }
                if (batch.isEmpty()) emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception failure) { close(); }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            sessions.remove(id);
            if (future != null) future.cancel(false);
            emitter.complete();
        }
    }
}
