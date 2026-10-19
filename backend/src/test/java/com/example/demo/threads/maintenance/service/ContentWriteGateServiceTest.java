package com.example.demo.threads.maintenance.service;

import com.example.demo.threads.maintenance.entity.ContentSearchControl;
import com.example.demo.threads.maintenance.repo.ContentSearchControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:content-search-gate;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ContentWriteGateServiceTest {
    @Autowired ContentWriteGateService gate;
    @Autowired ContentSearchControlRepository repository;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetControl() {
        transactionTemplate.executeWithoutResult(status -> {
            ContentSearchControl control = repository.findById(1L).orElseThrow();
            control.setPauseOwner(null);
            control.setPauseUntil(null);
            control.setActiveIndex(null);
            control.setReady(false);
        });
    }

    @Test
    void enterRequiresTheCallerWriteTransaction() {
        assertThatThrownBy(() -> gate.enter()).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void activePauseReturns503AndOnlyCurrentOwnerMayFinish() {
        gate.begin("owner-a");

        assertThatThrownBy(() -> inTransaction(gate::enter))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> gate.finish("owner-b", "index-b", () -> { }))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void expiredLeaseCannotFinish() {
        gate.begin("owner-a");
        inTransaction(() -> {
            ContentSearchControl control = repository.findById(1L).orElseThrow();
            control.setPauseUntil(Instant.now().minusSeconds(1));
        });

        assertThatThrownBy(() -> gate.finish("owner-a", "index-a", () -> { }))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void beginWaitsForAnUncommittedSharedWriterBeforePausing() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> writer = executor.submit(() -> inTransaction(() -> {
                gate.enter();
                entered.countDown();
                await(release);
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> begin = executor.submit(() -> gate.begin("owner-a"));
            assertThatThrownBy(() -> begin.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            begin.get(10, TimeUnit.SECONDS);
            assertThat(gate.snapshot().paused()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void invalidation_is_guarded_by_the_observed_index_and_maintenance_owner() {
        gate.begin("owner-a");
        gate.finish("owner-a", "index-a", () -> { });
        gate.invalidateIfActive("old-index");
        assertThat(gate.snapshot().ready()).isTrue();
        gate.begin("owner-b");
        gate.invalidateForOwner("owner-a");
        assertThat(gate.snapshot().ready()).isTrue();
        gate.invalidateForOwner("owner-b");
        assertThat(gate.snapshot().ready()).isFalse();
        assertThat(gate.snapshot().paused()).isTrue();
        gate.finish("owner-b", "index-b", () -> { });
        gate.invalidateIfActive("index-a");
        assertThat(gate.snapshot().ready()).isTrue();
        gate.invalidateIfActive("index-b");
        assertThat(gate.snapshot().ready()).isFalse();
    }

    @Test
    void finishPublishesNewIndexAndReleasesPauseAfterAliasSwitch() {
        gate.begin("owner-a");
        gate.finish("owner-a", "index-a", () -> { });

        ContentWriteGateService.Snapshot snapshot = gate.snapshot();
        assertThat(snapshot.ready()).isTrue();
        assertThat(snapshot.activeIndex()).isEqualTo("index-a");
        assertThat(snapshot.paused()).isFalse();
        assertThat(snapshot.pauseUntil()).isNull();
    }

    private void inTransaction(Runnable operation) {
        transactionTemplate.executeWithoutResult(status -> operation.run());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
