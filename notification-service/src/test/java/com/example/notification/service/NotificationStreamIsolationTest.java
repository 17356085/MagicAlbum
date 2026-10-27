package com.example.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationStreamIsolationTest {
    @Test
    void twoBlockedWritersCannotStarveAnotherOwnerOrStartOverlappingPolls() throws Exception {
        ProbeEmitter first = new ProbeEmitter(true, false);
        ProbeEmitter second = new ProbeEmitter(true, false);
        ProbeEmitter healthy = new ProbeEmitter(false, false);
        try (Fixture fixture = new Fixture(first, second, healthy)) {
            fixture.open(1L, 10);
            fixture.open(2L, 10);
            assertThat(first.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(second.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();

            fixture.open(3L, 10);
            await().atMost(Duration.ofSeconds(2)).until(() -> healthy.sends.get() >= 4);

            assertThat(first.sends).hasValue(1);
            assertThat(second.sends).hasValue(1);
            verify(fixture.events, times(1)).after(1L, 0, 50);
            verify(fixture.events, times(1)).after(2L, 0, 50);
        }
    }

    @Test
    void blockedCompletionCannotStarveOtherDeadlinesOrBlockServiceShutdown() throws Exception {
        ProbeEmitter first = new ProbeEmitter(false, true);
        ProbeEmitter second = new ProbeEmitter(false, true);
        ProbeEmitter healthy = new ProbeEmitter(false, false);
        try (Fixture fixture = new Fixture(first, second, healthy)) {
            fixture.openUntil(1L, Instant.now().plusMillis(250));
            fixture.openUntil(2L, Instant.now().plusMillis(250));
            assertThat(first.completeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(second.completeEntered.await(2, TimeUnit.SECONDS)).isTrue();

            fixture.openUntil(3L, Instant.now().plusMillis(250));
            assertThat(healthy.completeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(1), fixture.service::close);
        }
    }

    @Test
    void completionWaitingOnAnEmitterWriteLockDoesNotBlockOtherExpiryOrShutdown() throws Exception {
        ProbeEmitter first = new WriteLockedEmitter();
        ProbeEmitter second = new WriteLockedEmitter();
        ProbeEmitter healthy = new ProbeEmitter(false, false);
        try (Fixture fixture = new Fixture(first, second, healthy)) {
            fixture.openUntil(1L, Instant.now().plusMillis(300));
            fixture.openUntil(2L, Instant.now().plusMillis(300));
            assertThat(first.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(second.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();

            fixture.openUntil(3L, Instant.now().plusMillis(500));
            assertThat(healthy.completeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // Both completion tasks still wait for their own send/write lock.
            assertThat(first.completeEntered.getCount()).isEqualTo(1);
            assertThat(second.completeEntered.getCount()).isEqualTo(1);
            assertTimeoutPreemptively(Duration.ofSeconds(1), fixture.service::close);
        }
    }

    @Test
    void closingConnectionsKeepTheirSlotsUntilBlockedWritersActuallyFinish() throws Exception {
        ProbeEmitter first = new ProbeEmitter(true, false);
        ProbeEmitter second = new ProbeEmitter(true, false);
        try (Fixture fixture = new Fixture(first, second)) {
            fixture.openUntil(7L, Instant.now().plusMillis(500));
            fixture.openUntil(7L, Instant.now().plusMillis(500));
            assertThat(first.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(second.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(first.completeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(second.completeEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> fixture.open(7L, 10))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

            first.releaseSend.countDown();
            second.releaseSend.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(fixture.service.open(7L, Instant.now().plusSeconds(10), 0L)).isNotNull();
            });
        }
    }

    @Test
    void newConnectionsSendResetBeforeEventsAndReadInitialCursorOnlyOnce() throws Exception {
        ProbeEmitter emitter = new ProbeEmitter(false, false);
        try (Fixture fixture = new Fixture(emitter)) {
            fixture.service.open(9L, Instant.now().plusSeconds(10), null);
            await().atMost(Duration.ofSeconds(2)).until(() -> emitter.frames.size() >= 2);

            assertThat(emitter.frames.stream().toList().getFirst()).contains("event:reset", "id:0", "kind=reset");
            assertThat(emitter.frames.stream().toList().get(1)).contains("event:notification", "id:1");
            verify(fixture.events, times(1)).current(9L);
        }
    }

    @Test
    void admissionStaysBoundedAndShutdownRejectsNewConnections() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (long owner = 1; owner <= 128; owner++) fixture.open(owner, 10);
            assertThatThrownBy(() -> fixture.open(129L, 10))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

            fixture.service.close();
            assertThatThrownBy(() -> fixture.open(130L, 10))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final NotificationEventLog events = mock(NotificationEventLog.class);
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2);
        final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
        final Queue<ProbeEmitter> created = new ConcurrentLinkedQueue<>();
        final NotificationStreamService service;

        Fixture(ProbeEmitter... emitters) {
            Queue<ProbeEmitter> scripted = new ArrayDeque<>(List.of(emitters));
            when(events.current(anyLong())).thenReturn(0L);
            when(events.after(anyLong(), anyLong(), eq(50))).thenAnswer(call -> {
                long cursor = call.getArgument(1);
                return cursor == 0 ? List.of(new NotificationEventLog.Event("1", "12", "created")) : List.of();
            });
            service = new NotificationStreamService(events, scheduler, senders, timeout -> {
                ProbeEmitter emitter = scripted.isEmpty() ? new ProbeEmitter(false, false) : scripted.remove();
                created.add(emitter);
                return emitter;
            }, 20);
        }

        void open(long owner, long seconds) {
            openUntil(owner, Instant.now().plusSeconds(seconds));
        }

        void openUntil(long owner, Instant expiry) {
            service.open(owner, expiry, 0L);
        }

        @Override
        public void close() throws InterruptedException {
            created.forEach(emitter -> {
                emitter.releaseSend.countDown();
                emitter.releaseComplete.countDown();
            });
            service.close();
            assertThat(senders.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static class ProbeEmitter extends SseEmitter {
        final CountDownLatch sendEntered = new CountDownLatch(1);
        final CountDownLatch completeEntered = new CountDownLatch(1);
        final CountDownLatch releaseSend;
        final CountDownLatch releaseComplete;
        final AtomicInteger sends = new AtomicInteger();
        final Queue<String> frames = new ConcurrentLinkedQueue<>();

        ProbeEmitter(boolean blockSend, boolean blockComplete) {
            releaseSend = new CountDownLatch(blockSend ? 1 : 0);
            releaseComplete = new CountDownLatch(blockComplete ? 1 : 0);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            frames.add(frame.toString());
            sendEntered.countDown();
            try {
                if (!releaseSend.await(5, TimeUnit.SECONDS)) throw new IOException("Test writer was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }

        @Override
        public void complete() {
            completeEntered.countDown();
            try {
                if (!releaseComplete.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test completion was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Mirrors Spring's shared write lock around both send and complete. */
    private static final class WriteLockedEmitter extends ProbeEmitter {
        private final ReentrantLock writeLock = new ReentrantLock();

        WriteLockedEmitter() { super(true, false); }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            writeLock.lock();
            try { super.send(builder); }
            finally { writeLock.unlock(); }
        }

        @Override
        public void complete() {
            writeLock.lock();
            try { super.complete(); }
            finally { writeLock.unlock(); }
        }
    }
}
