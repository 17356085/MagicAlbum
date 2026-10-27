package com.example.demo.notifications.outbox;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.notifications.client.mode=remote", "app.notifications.client.enabled=true",
        "app.notifications.client.internal-service-token=integration-test-token-0123456789-abcdef",
        "app.notifications.outbox.scheduling-enabled=false", "app.notifications.outbox.backoff-base=1ms",
        "app.notifications.outbox.lease-duration=30s", "app.notifications.outbox.batch-size=1",
        "spring.datasource.url=jdbc:h2:mem:outbox-ack-contract;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "feature.mp.read.threads=false",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
@ActiveProfiles("test")
@DisabledInAotMode
class NotificationOutboxAckIntegrationTest {
    @Autowired NotificationOutboxService outbox;
    @Autowired NotificationOutboxRepository repository;
    @Autowired NotificationOutboxDispatcher dispatcher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationService downstream;
    @MockitoBean UserSettingsService preferences;
    @MockitoBean StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        when(preferences.shouldDeliver(anyLong(), anyString())).thenReturn(true);
    }

    static Stream<Arguments> invalidReceipts() {
        NotificationDto wrongTarget = receipt(900L);
        wrongTarget.setTargetId(99L);
        NotificationDto missingTitle = receipt(900L);
        missingTitle.setTitle(null);
        return Stream.of(
                Arguments.of("zero id", receipt(0L)),
                Arguments.of("negative id", receipt(-1L)),
                Arguments.of("wrong target", wrongTarget),
                Arguments.of("missing title", missingTitle),
                Arguments.of("id only", new NotificationDto(900L, null, null, null, false, null))
        );
    }

    @ParameterizedTest(name = "keeps {0} pending")
    @MethodSource("invalidReceipts")
    void unconfirmedAckKeepsTheDurableRowPendingThenRetriesWithTheSameEventId(String name, NotificationDto bad) {
        UUID event = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                outbox.enqueue(event, 101L, "reply", "Title", "Content", 7L, 8L, "post").orElseThrow());
        when(downstream.create(101L, "reply", "Title", "Content", 7L, 8L, "post", event))
                .thenReturn(bad, receipt(900L));

        makeEligible(repository.findByEventId(event.toString()).orElseThrow().getId());
        dispatcher.dispatch();
        var pending = repository.findByEventId(event.toString()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getLastError()).doesNotContain("Title", "Content");
        makeEligible(pending.getId());
        dispatcher.dispatch();
        var delivered = repository.findById(pending.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(delivered.getAttempts()).isEqualTo(2);
        verify(downstream, times(2)).create(101L, "reply", "Title", "Content", 7L, 8L, "post", event);
    }

    @Test
    void readReplayAndNullTargetMetadataDoNotPreventDelivery() {
        UUID event = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                outbox.enqueue(event, 101L, "system", "Title", "Content", null, null, null).orElseThrow());
        when(downstream.create(101L, "system", "Title", "Content", null, null, null, event))
                .thenReturn(new NotificationDto(900L, "system", "Title", "Content", true, Instant.now()));
        makeEligible(repository.findByEventId(event.toString()).orElseThrow().getId());
        dispatcher.dispatch();
        assertThat(repository.findByEventId(event.toString()).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
    }

    static Stream<Arguments> mixedTargets() {
        return Stream.of(
                Arguments.of("follow user", "follow", null, 8L, "user"),
                Arguments.of("thread without comment", "system", 7L, null, "thread"),
                Arguments.of("nullable target type", "system", 7L, 8L, null)
        );
    }

    @ParameterizedTest(name = "delivers {0}")
    @MethodSource("mixedTargets")
    void mixedNullableTargetsDoNotStrandAnOtherwiseConfirmedDelivery(String name, String type, Long thread, Long target, String targetType) {
        UUID event = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                outbox.enqueue(event, 101L, type, "Title", "Content", thread, target, targetType).orElseThrow());
        when(downstream.create(101L, type, "Title", "Content", thread, target, targetType, event))
                .thenReturn(new NotificationDto(900L, type, "Title", "Content", true, Instant.now(), thread, target, targetType));
        makeEligible(repository.findByEventId(event.toString()).orElseThrow().getId());
        dispatcher.dispatch();
        var delivered = repository.findByEventId(event.toString()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(delivered.getAttempts()).isEqualTo(1);
        verify(downstream).create(101L, type, "Title", "Content", thread, target, targetType, event);
    }

    private static NotificationDto receipt(Long id) {
        return new NotificationDto(id, "reply", "Title", "Content", true, Instant.now(), 7L, 8L, "post");
    }

    private void makeEligible(Long id) {
        // This class verifies acknowledgements, not wall-clock eligibility.
        jdbc.update("UPDATE notification_outbox SET available_at=? WHERE id=?",
                Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), id);
    }
}
