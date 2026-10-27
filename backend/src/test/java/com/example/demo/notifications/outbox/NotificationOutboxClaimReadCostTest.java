package com.example.demo.notifications.outbox;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression guard for Hibernate entity materialization during one-row claims. */
@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-outbox-claim-read-cost;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=false",
        "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.orm.jdbc.bind=OFF",
        "app.notifications.client.mode=mock",
        "app.notifications.client.enabled=true",
        "app.notifications.outbox.scheduling-enabled=false",
        "app.notifications.outbox.lease-duration=30s",
        "app.notifications.outbox.batch-size=50",
        "app.notifications.outbox.max-attempts=3",
        "app.notifications.outbox.backoff-base=1ms",
        "app.notifications.outbox.retention=1d"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationOutboxClaimReadCostTest {
    private static final int BACKLOG_SIZE = 150;
    private static final int CLAIM_COUNT = 50;
    private static final int EXPECTED_REMAINING_PER_KIND = 100;

    @Autowired
    private NotificationOutboxRepository repository;

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void removePriorScenarioRows() {
        repository.deleteAllInBatch();
        repository.flush();
    }

    @Test
    @Order(1)
    void pending_backlog_claims_stay_within_candidate_read_bound() {
        runScenario("pending", true, false);
    }

    @Test
    @Order(2)
    void expired_lease_backlog_claims_stay_within_candidate_read_bound() {
        runScenario("expired_lease", false, true);
    }

    @Test
    @Order(3)
    void mixed_backlog_claims_stay_within_two_candidate_read_bound() {
        runScenario("mixed", true, true);
    }

    private void runScenario(String scenario, boolean includePending, boolean includeExpired) {
        Instant seedNow = Instant.now();
        int initialPending = includePending ? BACKLOG_SIZE : 0;
        int initialExpired = includeExpired ? BACKLOG_SIZE : 0;
        if (includePending) {
            seedRows("pending-" + scenario, NotificationOutboxStatus.PENDING, BACKLOG_SIZE, seedNow);
        }
        if (includeExpired) {
            seedRows("expired-" + scenario, NotificationOutboxStatus.IN_FLIGHT, BACKLOG_SIZE, seedNow);
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<NotificationOutboxService.Delivery> deliveries = claimAndDeliver(scenario);
        long entityLoads = statistics.getEntityLoadCount();
        long preparedStatements = statistics.getPrepareStatementCount();

        Set<Long> claimedIds = new LinkedHashSet<>();
        for (NotificationOutboxService.Delivery delivery : deliveries) {
            claimedIds.add(delivery.id());
            assertThat(delivery.attempts()).isEqualTo(1);
        }
        assertThat(deliveries).hasSize(CLAIM_COUNT);
        assertThat(claimedIds).hasSize(CLAIM_COUNT);

        int finalRows = countRowsByIds(claimedIds);
        int deliveredRows = countRowsByIdsAndStatus(claimedIds, NotificationOutboxStatus.DELIVERED);
        int deliveredAttemptRows = countRowsByIdsAndStatusAndAttempts(
                claimedIds, NotificationOutboxStatus.DELIVERED, 1);
        long remainingPending = countByStatus(NotificationOutboxStatus.PENDING);
        long remainingExpired = countByStatus(NotificationOutboxStatus.IN_FLIGHT);

        assertThat(finalRows).isEqualTo(CLAIM_COUNT);
        assertThat(deliveredRows).isEqualTo(CLAIM_COUNT);
        assertThat(deliveredAttemptRows).isEqualTo(CLAIM_COUNT);
        if (includePending) {
            assertThat(remainingPending).isGreaterThanOrEqualTo(EXPECTED_REMAINING_PER_KIND);
        }
        if (includeExpired) {
            assertThat(remainingExpired).isGreaterThanOrEqualTo(EXPECTED_REMAINING_PER_KIND);
        }

        System.out.println("OUTBOX_CLAIM_READ_COST " + marker(
                scenario,
                initialPending,
                initialExpired,
                deliveries,
                claimedIds,
                entityLoads,
                preparedStatements,
                finalRows,
                deliveredRows,
                deliveredAttemptRows,
                remainingPending,
                remainingExpired
        ));
        System.out.flush();

        // A one-row claim may materialize at most one candidate per eligible status.
        assertThat(entityLoads)
                .as("entity loads for 50 sequential claims in %s", scenario)
                .isLessThanOrEqualTo(100);
    }

    private List<NotificationOutboxService.Delivery> claimAndDeliver(String scenario) {
        String owner = "read-cost-" + scenario;
        List<NotificationOutboxService.Delivery> deliveries = new ArrayList<>();
        for (int index = 0; index < CLAIM_COUNT; index++) {
            var claimed = outboxService.claimOne(owner);
            if (claimed.isEmpty()) {
                throw new AssertionError("claimOne returned empty at index " + index + " for " + scenario);
            }
            NotificationOutboxService.Delivery delivery = claimed.get();
            assertThat(outboxService.markDelivered(delivery.id(), owner)).isTrue();
            deliveries.add(delivery);
        }
        return deliveries;
    }

    private void seedRows(String prefix, NotificationOutboxStatus status, int count, Instant seedNow) {
        Instant dueStart = seedNow.minusSeconds(1_000);
        List<NotificationOutbox> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Instant dueAt = dueStart.plusSeconds(index);
            NotificationOutbox row = new NotificationOutbox();
            row.setEventId(prefix + "-" + String.format("%03d", index));
            row.setRecipientUserId(101L);
            row.setType("reply");
            row.setTitle("Title");
            row.setContent("Content");
            row.setStatus(status);
            row.setAttempts(0);
            row.setAvailableAt(dueAt);
            row.setLeaseOwner(status == NotificationOutboxStatus.IN_FLIGHT ? "seed-owner" : null);
            row.setLeaseUntil(status == NotificationOutboxStatus.IN_FLIGHT ? dueAt : null);
            row.setCreatedAt(dueAt);
            row.setUpdatedAt(dueAt);
            rows.add(row);
        }
        repository.saveAll(rows);
        repository.flush();
    }

    private int countRowsByIds(Collection<Long> ids) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE id IN (" + placeholders(ids.size()) + ")",
                Integer.class,
                ids.toArray(new Object[0])
        );
    }

    private int countRowsByIdsAndStatus(Collection<Long> ids, NotificationOutboxStatus status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE status = ? AND id IN ("
                        + placeholders(ids.size()) + ")",
                Integer.class,
                prepend(status.name(), ids)
        );
    }

    private int countRowsByIdsAndStatusAndAttempts(
            Collection<Long> ids,
            NotificationOutboxStatus status,
            int attempts
    ) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE status = ? AND attempts = ? AND id IN ("
                        + placeholders(ids.size()) + ")",
                Integer.class,
                prepend(status.name(), attempts, ids)
        );
    }

    private long countByStatus(NotificationOutboxStatus status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE status = ?",
                Long.class,
                status.name()
        );
    }

    private static String placeholders(int count) {
        return "?, ".repeat(Math.max(0, count - 1)) + "?";
    }

    private static Object[] prepend(Object first, Collection<Long> ids) {
        Object[] values = new Object[ids.size() + 1];
        values[0] = first;
        int index = 1;
        for (Long id : ids) {
            values[index++] = id;
        }
        return values;
    }

    private static Object[] prepend(Object first, Object second, Collection<Long> ids) {
        Object[] values = new Object[ids.size() + 2];
        values[0] = first;
        values[1] = second;
        int index = 2;
        for (Long id : ids) {
            values[index++] = id;
        }
        return values;
    }

    private static String marker(
            String scenario,
            int initialPending,
            int initialExpired,
            List<NotificationOutboxService.Delivery> deliveries,
            Set<Long> claimedIds,
            long entityLoads,
            long preparedStatements,
            int finalRows,
            int deliveredRows,
            int deliveredAttemptRows,
            long remainingPending,
            long remainingExpired
    ) {
        return "{"
                + "\"scenario\":\"" + scenario + "\""
                + ",\"initialPending\":" + initialPending
                + ",\"initialExpired\":" + initialExpired
                + ",\"claims\":" + deliveries.size()
                + ",\"distinctIds\":" + claimedIds.size()
                + ",\"entityLoads\":" + entityLoads
                + ",\"preparedStatements\":" + preparedStatements
                + ",\"finalRows\":" + finalRows
                + ",\"deliveredRows\":" + deliveredRows
                + ",\"deliveredAttemptRows\":" + deliveredAttemptRows
                + ",\"remainingPending\":" + remainingPending
                + ",\"remainingExpired\":" + remainingExpired
                + "}";
    }
}
