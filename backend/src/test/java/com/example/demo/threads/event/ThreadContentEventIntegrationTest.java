package com.example.demo.threads.event;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.tags.service.TagService;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventStatus;
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.event.service.ContentEventRecorder;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.rabbit.enabled=false",
        "spring.datasource.url=${CONTENT_EVENT_TEST_DATASOURCE_URL:jdbc:h2:mem:content-event-service;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false}",
        "spring.datasource.driver-class-name=${CONTENT_EVENT_TEST_DATASOURCE_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${CONTENT_EVENT_TEST_DATASOURCE_USERNAME:sa}",
        "spring.datasource.password=${CONTENT_EVENT_TEST_DATASOURCE_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=${CONTENT_EVENT_TEST_JPA_DDL_AUTO:create-drop}",
        "spring.jpa.properties.hibernate.dialect=${CONTENT_EVENT_TEST_HIBERNATE_DIALECT:org.hibernate.dialect.H2Dialect}",
        "spring.flyway.enabled=${CONTENT_EVENT_TEST_FLYWAY_ENABLED:false}",
        "spring.jpa.show-sql=false"
})
class ThreadContentEventIntegrationTest {
    @Autowired ThreadService threadService;
    @Autowired ThreadRepository threadRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired UserRepository userRepository;
    @Autowired ContentEventOutboxRepository eventRepository;
    @Autowired ContentEventRecorder eventRecorder;
    @Autowired TagService tagService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired DataSource dataSource;
    @MockitoBean StringRedisTemplate redis;

    @Value("${CONTENT_EVENT_TEST_EXPECT_MYSQL:false}")
    private boolean expectMysql;

    @Value("${CONTENT_EVENT_TEST_EXPECTED_DATABASE:}")
    private String expectedDatabase;

    private Long authorId;
    private Long otherUserId;
    private Long sectionId;
    private Long secondSectionId;

    @BeforeEach
    void seedData() {
        assertDatabaseGuard();
        jdbc.update("DELETE FROM content_event_outbox");
        jdbc.update("DELETE FROM thread_tags");
        jdbc.update("DELETE FROM tags");
        threadRepository.deleteAll();
        sectionRepository.deleteAll();
        userRepository.deleteAll();

        authorId = saveUser("content-author", "13000000101");
        otherUserId = saveUser("content-other", "13000000102");
        sectionId = saveSection("content-events", "Content events");
        secondSectionId = saveSection("content-events-2", "Content events 2");
    }

    private void assertDatabaseGuard() {
        if (!expectMysql) {
            return;
        }
        assertThat(expectedDatabase)
                .as("CONTENT_EVENT_TEST_EXPECTED_DATABASE is required for MySQL acceptance")
                .matches("ba_content_runtime_[0-9a-f]{10,32}");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .as("content event acceptance must use MySQL")
                    .isEqualToIgnoringCase("MySQL");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect the content event test database", exception);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(expectedDatabase);
    }

    @Test
    void all_content_commands_commit_versions_events_and_real_tags_together() {
        CreateThreadRequest create = new CreateThreadRequest();
        create.setSectionId(sectionId);
        create.setTitle("Original title");
        create.setContent("Original body");
        create.setTags(List.of("first-tag"));

        Long threadId = threadService.create(authorId, create).getId();
        assertThreadVersion(threadId, 1L, "NORMAL");
        assertThat(tagService.getNamesByThreadId(threadId)).containsExactly("first-tag");
        assertEvent(threadId, 1L, ContentEventType.THREAD_UPSERTED);

        UpdateThreadRequest tagsOnly = new UpdateThreadRequest();
        tagsOnly.setTags(List.of("second-tag"));
        threadService.update(authorId, threadId, tagsOnly);
        assertThreadVersion(threadId, 2L, "NORMAL");
        assertThat(tagService.getNamesByThreadId(threadId)).containsExactly("second-tag");
        assertEvent(threadId, 2L, ContentEventType.THREAD_UPSERTED);

        UpdateThreadRequest sectionOnly = new UpdateThreadRequest();
        sectionOnly.setSectionId(secondSectionId);
        threadService.update(authorId, threadId, sectionOnly);
        assertThreadVersion(threadId, 3L, "NORMAL");
        assertEvent(threadId, 3L, ContentEventType.THREAD_UPSERTED);

        threadService.hideForModeration(threadId);
        assertThreadVersion(threadId, 4L, "HIDDEN");
        assertEvent(threadId, 4L, ContentEventType.THREAD_HIDDEN);

        threadService.delete(authorId, threadId);
        assertThreadVersion(threadId, 5L, "DELETED");
        assertEvent(threadId, 5L, ContentEventType.THREAD_DELETED);

        threadService.delete(authorId, threadId);
        assertThat(eventRepository.findAll()).hasSize(5);
    }

    @Test
    void caller_rollback_reverts_thread_version_event_and_real_tag_write() {
        Long threadId = saveThread("before", "body", "NORMAL");
        UpdateThreadRequest request = new UpdateThreadRequest();
        request.setContent("after");
        request.setTags(List.of("rolled-back-tag"));

        transactionTemplate.executeWithoutResult(status -> {
            threadService.update(authorId, threadId, request);
            status.setRollbackOnly();
        });

        assertThreadVersion(threadId, 0L, "NORMAL");
        assertThat(threadRepository.findById(threadId).orElseThrow().getContentMd()).isEqualTo("body");
        assertThat(tagService.getNamesByThreadId(threadId)).isEmpty();
        assertThat(eventRepository.findAll()).isEmpty();
    }

    @Test
    void actual_outbox_constraint_failure_rolls_back_thread_and_tags() {
        Long threadId = saveThread("before", "body", "NORMAL");
        insertEvent(threadId, 1L, ContentEventType.THREAD_UPSERTED);
        UpdateThreadRequest request = new UpdateThreadRequest();
        request.setContent("after");
        request.setTags(List.of("constraint-failure-tag"));

        assertThatThrownBy(() -> threadService.update(authorId, threadId, request))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThreadVersion(threadId, 0L, "NORMAL");
        assertThat(threadRepository.findById(threadId).orElseThrow().getContentMd()).isEqualTo("body");
        assertThat(tagService.getNamesByThreadId(threadId)).isEmpty();
        assertThat(eventRepository.findAll()).hasSize(1);
    }

    @Test
    void permission_and_hidden_checks_do_not_write_events_and_delete_is_idempotent() {
        Long threadId = saveThread("before", "body", "NORMAL");
        UpdateThreadRequest request = new UpdateThreadRequest();
        request.setContent("forbidden");

        assertThatThrownBy(() -> threadService.update(otherUserId, threadId, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
        assertThat(eventRepository.findAll()).isEmpty();
        assertThreadVersion(threadId, 0L, "NORMAL");

        assertThatThrownBy(() -> threadService.delete(otherUserId, threadId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
        assertThat(eventRepository.findAll()).isEmpty();

        threadService.hideForModeration(threadId);
        assertThatThrownBy(() -> threadService.update(authorId, threadId, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        assertThat(eventRepository.findAll()).hasSize(1);
        assertThreadVersion(threadId, 1L, "HIDDEN");

        threadService.delete(authorId, threadId);
        assertThatThrownBy(() -> threadService.delete(otherUserId, threadId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
        threadService.delete(authorId, threadId);
        assertThat(eventRepository.findAll()).hasSize(2);
        assertThreadVersion(threadId, 2L, "DELETED");
    }

    @Test
    void concurrent_edits_use_locked_versions_without_duplicate_aggregate_versions() throws Exception {
        Long threadId = saveThread("before", "body", "NORMAL");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> updateConcurrently(threadId, "body-one", ready, start));
            Future<?> second = executor.submit(() -> updateConcurrently(threadId, "body-two", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThreadVersion(threadId, 2L, "NORMAL");
        List<Long> versions = eventRepository.findAll().stream()
                .filter(event -> threadId.equals(event.getAggregateId()))
                .map(ContentEventOutbox::getAggregateVersion)
                .sorted()
                .toList();
        assertThat(versions).containsExactly(1L, 2L);
    }

    @Test
    void mandatory_recorder_rejects_missing_transaction_and_invalid_request_id_is_dropped() {
        assertThatThrownBy(() -> eventRecorder.record(1L, 1L, ContentEventType.THREAD_UPSERTED))
                .isInstanceOf(IllegalTransactionStateException.class);

        Long threadId = saveThread("before", "body", "NORMAL");
        UpdateThreadRequest validRequest = new UpdateThreadRequest();
        validRequest.setContent("after-valid-request");
        MDC.put("requestId", "content.req:2026-10-19");
        try {
            threadService.update(authorId, threadId, validRequest);
        } finally {
            MDC.remove("requestId");
        }

        UpdateThreadRequest invalidRequest = new UpdateThreadRequest();
        invalidRequest.setContent("after-invalid-request");
        MDC.put("requestId", "invalid request id");
        try {
            threadService.update(authorId, threadId, invalidRequest);
        } finally {
            MDC.remove("requestId");
        }

        List<ContentEventOutbox> events = eventRepository.findAll().stream()
                .sorted(java.util.Comparator.comparingLong(ContentEventOutbox::getAggregateVersion))
                .toList();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getRequestId()).isEqualTo("content.req:2026-10-19");
        assertThat(events.get(1).getRequestId()).isNull();
        for (ContentEventOutbox event : events) {
            assertThat(event.getEventId()).matches("[0-9a-f-]{36}");
            assertThat(event.getSchemaVersion()).isEqualTo(1);
            assertThat(event.getAggregateType()).isEqualTo("THREAD");
            assertThat(event.getStatus()).isEqualTo(ContentEventStatus.PENDING);
            assertThat(event.getOccurredAt()).isNotNull();
        }
        String schemaFunction = expectMysql ? "DATABASE()" : "CURRENT_SCHEMA()";
        List<String> columns = jdbc.queryForList(
                "SELECT UPPER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_SCHEMA)=UPPER(" + schemaFunction + ") "
                        + "AND UPPER(TABLE_NAME)='CONTENT_EVENT_OUTBOX'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "ID", "EVENT_ID", "SCHEMA_VERSION", "AGGREGATE_TYPE", "AGGREGATE_ID",
                "AGGREGATE_VERSION", "EVENT_TYPE", "OCCURRED_AT", "REQUEST_ID", "STATUS");
    }

    private void updateConcurrently(Long threadId,
                                    String content,
                                    CountDownLatch ready,
                                    CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            UpdateThreadRequest request = new UpdateThreadRequest();
            request.setContent(content);
            threadService.update(authorId, threadId, request);
        } catch (InterruptedException exception) {
            java.lang.Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Long saveThread(String title, String body, String status) {
        Thread thread = new Thread();
        thread.setSectionId(sectionId);
        thread.setAuthorId(authorId);
        thread.setTitle(title);
        thread.setContentMd(body);
        thread.setStatus(status);
        thread.setContentVersion(0L);
        return threadRepository.saveAndFlush(thread).getId();
    }

    private Long saveUser(String username, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@content-events.test");
        user.setPhone(phone);
        user.setPasswordHash("test-hash");
        return userRepository.save(user).getId();
    }

    private Long saveSection(String slug, String name) {
        Section section = new Section();
        section.setSlug(slug);
        section.setName(name);
        return sectionRepository.save(section).getId();
    }

    private void insertEvent(Long threadId, long version, ContentEventType eventType) {
        ContentEventOutbox event = new ContentEventOutbox();
        event.setEventId(UUID.randomUUID().toString());
        event.setSchemaVersion(1);
        event.setAggregateType("THREAD");
        event.setAggregateId(threadId);
        event.setAggregateVersion(version);
        event.setEventType(eventType);
        event.setOccurredAt(Instant.now());
        event.setStatus(ContentEventStatus.PENDING);
        eventRepository.saveAndFlush(event);
    }

    private void assertEvent(Long threadId, long version, ContentEventType eventType) {
        ContentEventOutbox event = eventRepository.findAll().stream()
                .filter(candidate -> threadId.equals(candidate.getAggregateId())
                        && candidate.getAggregateVersion() == version)
                .findFirst()
                .orElseThrow();
        assertThat(event.getEventType()).isEqualTo(eventType);
        assertThat(event.getRequestId()).isNull();
    }

    private void assertThreadVersion(Long threadId, long version, String status) {
        Thread thread = threadRepository.findById(threadId).orElseThrow();
        assertThat(thread.getContentVersion()).isEqualTo(version);
        assertThat(thread.getStatus()).isEqualTo(status);
    }
}
