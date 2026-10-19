package com.example.demo.search;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.search.port.SearchVersionConflictException;
import com.example.demo.search.service.ContentSearchConsumer;
import com.example.demo.search.service.ContentSearchReplayService;
import com.example.demo.search.service.SearchRebuildService;
import com.example.demo.search.service.ThreadSearchService;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.CreateThreadRequest;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.dto.UpdateThreadRequest;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.entity.ContentEventStatus;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.event.service.ContentEventOutboxService;
import com.example.demo.threads.event.service.ContentEventPublisherTransport;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit-only acceptance. Dependencies are created and stopped by content-search-smoke.sh. */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=${CONTENT_SEARCH_TEST_DATASOURCE_URL}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=${CONTENT_SEARCH_TEST_DB_USERNAME}",
        "spring.datasource.password=${CONTENT_SEARCH_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=true",
        "spring.rabbitmq.host=127.0.0.1",
        "spring.rabbitmq.port=${CONTENT_SEARCH_TEST_RABBIT_PORT}",
        "spring.rabbitmq.username=${CONTENT_SEARCH_TEST_RABBIT_USERNAME}",
        "spring.rabbitmq.password=${CONTENT_SEARCH_TEST_RABBIT_PASSWORD}",
        "spring.rabbitmq.connection-timeout=1s",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.rabbit.enabled=true",
        "app.content.events.enabled=true",
        "app.content.events.publisher-scheduler-enabled=false",
        "app.content.events.max-attempts=2",
        "app.content.events.confirm-timeout=1s",
        "app.content.events.connection-timeout=1s",
        "app.search.enabled=true",
        "app.search.consumer.enabled=false",
        "app.search.consumer.max-attempts=2",
        "app.search.consumer.backoff-ms=10",
        "app.search.elasticsearch.url=${CONTENT_SEARCH_TEST_ELASTIC_URL}",
        "app.search.elasticsearch.connect-timeout=500ms",
        "app.search.elasticsearch.read-timeout=1s",
        "app.search.elasticsearch.replicas=0"
})
class ContentSearchRuntimeAcceptance {
    private static final String QUEUE = "content.search.v1";
    private static final String FAILED = "content.search.failed.v1";
    private static final String QUARANTINE = "content.search.quarantine.v1";
    @Autowired ThreadService threads;
    @Autowired ThreadRepository threadRepository;
    @Autowired SectionRepository sections;
    @Autowired UserRepository users;
    @Autowired ContentEventOutboxRepository events;
    @Autowired ContentEventOutboxService outbox;
    @Autowired ContentEventPublisherTransport publisher;
    @Autowired ContentSearchConsumer consumer;
    @Autowired ContentSearchReplayService replay;
    @Autowired ContentSearchIndex index;
    @Autowired SearchRebuildService rebuild;
    @Autowired ThreadSearchService search;
    @Autowired ThreadSearchProjectionService owner;
    @Autowired ConnectionFactory connections;
    @Autowired ApplicationContext context;
    @Autowired WebApplicationContext web;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry metrics;
    @MockitoBean StringRedisTemplate redis;

    @BeforeEach
    void isolatedDatabaseGuard() throws Exception {
        String expected = System.getenv("CONTENT_SEARCH_TEST_DATABASE");
        assertThat(expected).matches("ba_search_runtime_[0-9a-f]{10,32}");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(expected);
    }

    @Test
    void pipeline_replay_order_visibility_and_rebuild() throws Exception {
        phase("pipeline");
        declareTopology();
        Actor actor = actor("pipeline");
        long id = create(actor, "M2OriginalNeedle", "M2OriginalNeedle body");
        ThreadSearchDocument original = owner.read(id).orElseThrow();
        var built = rebuild.rebuild();
        assertThat(built.documents()).isEqualTo(built.verified()).isPositive();
        MockMvcBuilders.webAppContextSetup(web).build().perform(post("/api/v1/admin/search/rebuild"))
                .andExpect(status().isUnauthorized());
        assertThat(index.activeIndex()).contains(built.index());
        publishPending();
        consumeAll();
        index.refresh(ContentSearchIndex.ALIAS);
        double indexedRequests = metrics.counter("content.search.requests", "source", "elasticsearch").count();
        assertThat(search.list("M2OriginalNeedle", null, null, 1, 10).getContent())
                .extracting(dto -> dto.getId()).contains(id);
        assertThat(metrics.counter("content.search.requests", "source", "elasticsearch").count())
                .isEqualTo(indexedRequests + 1);

        UpdateThreadRequest edit = new UpdateThreadRequest();
        edit.setTitle("M2CurrentNeedle");
        edit.setContent("M2CurrentNeedle edited body");
        edit.setTags(List.of("M2TAG"));
        threads.update(actor.user(), id, edit);
        publishPending();
        consumeAll();
        var current = owner.read(id).orElseThrow();
        index.put(ContentSearchIndex.ALIAS, original);
        index.put(ContentSearchIndex.ALIAS, current);
        assertThat(index.read(ContentSearchIndex.ALIAS, id)).contains(current);
        var conflict = new ThreadSearchDocument(id, current.version(), true, "wrong", "wrong", List.of(),
                current.sectionId(), current.createdAt(), UUID.randomUUID().toString(), current.eventType());
        assertThatThrownBy(() -> index.put(ContentSearchIndex.ALIAS, conflict))
                .isInstanceOf(SearchVersionConflictException.class);
        assertThat(index.read(ContentSearchIndex.ALIAS, id)).contains(current);
        index.refresh(ContentSearchIndex.ALIAS);
        assertThat(search.list("M2OriginalNeedle", null, null, 1, 10)).isEmpty();
        assertThat(search.list("M2CurrentNeedle", "m2tag", actor.section(), 1, 10).getContent())
                .extracting(dto -> dto.getId()).containsExactly(id);
        MockMvcBuilders.webAppContextSetup(web).build()
                .perform(get("/api/v1/threads").param("q", "M2CurrentNeedle").param("tag", "m2tag")
                        .param("sectionId", Long.toString(actor.section())).param("page", "1").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(10));

        // A routable confirm and an unroutable return have different outcomes.
        Connection connection = connections.createConnection();
        Channel channel = connection.createChannel(false);
        try {
            channel.queueUnbind(QUEUE, "content.events.v1", "thread.changed");
            assertThatThrownBy(() -> publisher.publish(message(current)))
                    .isInstanceOfSatisfying(ContentEventPublisherTransport.PublishException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ContentEventPublisherTransport.Code.RETURNED));
        } finally {
            channel.queueBind(QUEUE, "content.events.v1", "thread.changed");
            channel.close();
            connection.close();
        }

        // Delivery was applied, then the channel died before ACK; Rabbit redelivers it.
        publisher.publish(message(current));
        Connection firstConnection = connections.createConnection();
        Channel firstChannel = firstConnection.createChannel(false);
        GetResponse unacknowledged = awaitDelivery(firstChannel, QUEUE);
        consumer.apply(consumer.decode(unacknowledged.getBody()));
        firstChannel.abort();
        firstConnection.close();
        Connection nextConnection = connections.createConnection();
        Channel nextChannel = nextConnection.createChannel(false);
        try {
            GetResponse redelivered = awaitDelivery(nextChannel, QUEUE);
            assertThat(redelivered.getEnvelope().isRedeliver()).isTrue();
            consumer.onMessage(springMessage(redelivered), nextChannel);
        } finally {
            nextChannel.close();
            nextConnection.close();
        }
        assertThat(index.read(ContentSearchIndex.ALIAS, id)).contains(current);

        ContentEventMessage unknownSchema = new ContentEventMessage(UUID.randomUUID().toString(), 9,
                "THREAD", id, current.version(), "THREAD_UPSERTED", Instant.now(), "sensitive-original-request");
        publisher.publish(unknownSchema);
        consumeAll();
        assertThat(awaitQueueAtLeast(FAILED, 1)).isEqualTo(1);
        ContentEventMessage validAfterPoison = message(current);
        Connection failedQueueConnection = connections.createConnection();
        Channel failedQueueChannel = failedQueueConnection.createChannel(false);
        try {
            failedQueueChannel.confirmSelect();
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding(StandardCharsets.UTF_8.name())
                    .deliveryMode(2)
                    .messageId(validAfterPoison.eventId())
                    .build();
            failedQueueChannel.basicPublish("content.events.dlx", "failed", properties,
                    mapper.writeValueAsBytes(validAfterPoison));
            failedQueueChannel.waitForConfirmsOrDie(5000);
        } finally {
            failedQueueChannel.close();
            failedQueueConnection.close();
        }
        assertThat(awaitQueueAtLeast(FAILED, 2)).isEqualTo(2);
        var incompatibleReplay = replay.replay(2);
        assertThat(incompatibleReplay.published()).isEqualTo(1);
        assertThat(incompatibleReplay.quarantined()).isEqualTo(1);
        assertThat(incompatibleReplay.failureCode()).isNull();
        assertThat(incompatibleReplay.remaining()).isZero();
        assertThat(queueCount(FAILED)).isZero();
        consumeAll();
        assertThat(queueCount(QUEUE)).isZero();
        assertThat(index.read(ContentSearchIndex.ALIAS, id)).contains(current);
        assertThat(awaitQueueAtLeast(QUARANTINE, 1)).isEqualTo(1);
        String quarantineBody = takeQueueBody(QUARANTINE);
        assertThat(quarantineBody).contains("\"reason\":\"INVALID_ENVELOPE\"");
        assertThat(quarantineBody).doesNotContain("sensitive-original-request");
        assertThat(quarantineBody).doesNotContain("requestId");

        threads.hideForModeration(id);
        // Deliberately leave the old visible ES document in place before rechecking the owner.
        assertThat(search.list("M2CurrentNeedle", null, null, 1, 10)).isEmpty();
        publishPending();
        consumeAll();
        assertTombstone(id, 3);
        threads.delete(actor.user(), id);
        publishPending();
        consumeAll();
        index.put(ContentSearchIndex.ALIAS, original);
        assertTombstone(id, 4);
        long count = events.count();
        threads.delete(actor.user(), id);
        assertThat(events.count()).isEqualTo(count);

        // A committed publisher send followed by a process crash leaves a recoverable lease.
        long crashId = create(actor, "M2CrashNeedle", "M2CrashNeedle body");
        var leased = outbox.claimOne().orElseThrow();
        assertThat(leased.message().aggregateId()).isEqualTo(crashId);
        publisher.publish(leased.message());
        jdbc.update("UPDATE content_event_outbox SET lease_until=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(5)), leased.id());
        assertThat(outbox.markPublished(leased)).isFalse();
        var recovered = outbox.claimOne().orElseThrow();
        assertThat(recovered.message().eventId()).isEqualTo(leased.message().eventId());
        publisher.publish(recovered.message());
        assertThat(outbox.markPublished(recovered)).isTrue();
        consumeAll();
        assertThat(index.read(ContentSearchIndex.ALIAS, crashId)).contains(owner.read(crashId).orElseThrow());
        verifyLateCommitRebuild(actor);
        evidence("pipeline", Map.of("threadId", id, "tombstoneVersion", 4,
                "crashRecoveredThreadId", crashId, "activeIndex", index.activeIndex().orElseThrow(),
                "published", outbox.count(ContentEventStatus.PUBLISHED)));
    }

    @Test
    void index_outage_quarantines_and_keeps_owner_results() throws Exception {
        phase("index_outage");
        declareTopology();
        Actor actor = actor("indexoutage");
        long id = create(actor, "M2IndexOutageNeedle", "M2IndexOutageNeedle body");
        publishPending();
        consumeAll();
        assertThat(awaitQueueAtLeast(FAILED, 1)).isPositive();
        assertThat(search.list("M2IndexOutageNeedle", null, null, 1, 10).getContent())
                .extracting(dto -> dto.getId()).containsExactly(id);
        assertThat(metrics.counter("content.search.fallback", "reason", "unavailable").count()).isPositive();
        evidence("index_outage", Map.of("threadId", id, "durableFailedQueue", queueCount(FAILED),
                "databaseFallback", true));
    }

    @Test
    void outage_keeps_events_and_uses_database() throws Exception {
        phase("outage");
        Actor actor = actor("outage");
        long id = create(actor, "M2OutageNeedle", "M2OutageNeedle body");
        var delivery = outbox.claimOne().orElseThrow();
        assertThat(delivery.message().aggregateId()).isEqualTo(id);
        assertThatThrownBy(() -> publisher.publish(delivery.message()))
                .isInstanceOf(ContentEventPublisherTransport.PublishException.class);
        assertThat(outbox.markFailure(delivery, new IllegalStateException("offline"))).isTrue();
        assertThat(events.findById(delivery.id()).orElseThrow().getStatus()).isEqualTo(ContentEventStatus.PENDING);
        assertThat(search.list("M2OutageNeedle", null, null, 1, 10).getContent())
                .extracting(dto -> dto.getId()).containsExactly(id);
        evidence("outage", Map.of("threadId", id, "pendingEventId", delivery.message().eventId(),
                "pendingCount", outbox.count(ContentEventStatus.PENDING), "databaseFallback", true));
    }

    @Test
    void recovery_replays_pending_and_failed_events() throws Exception {
        phase("recovery");
        declareTopology();
        assertThat(queueCount(FAILED)).isPositive();
        publishPending();
        var retried = replay.replay(20);
        assertThat(retried.published()).isPositive();
        assertThat(retried.failureCode()).isNull();
        consumeAll();
        index.refresh(ContentSearchIndex.ALIAS);
        assertThat(outbox.count(ContentEventStatus.PENDING)).isZero();
        assertThat(queueCount(FAILED)).isZero();
        for (String needle : List.of("M2OutageNeedle", "M2IndexOutageNeedle")) {
            var result = search.list(needle, null, null, 1, 10);
            assertThat(result.getContent()).hasSize(1);
            long id = result.getContent().getFirst().getId();
            assertThat(index.read(ContentSearchIndex.ALIAS, id)).contains(owner.read(id).orElseThrow());
        }
        String oldIndex = index.activeIndex().orElseThrow();
        var rebuilt = rebuild.rebuild();
        assertThat(rebuilt.index()).isNotEqualTo(oldIndex);
        assertThat(index.count(oldIndex)).isPositive();
        assertThat(index.activeIndex()).contains(rebuilt.index());
        evidence("recovery", Map.of("failedReplayed", retried.published(), "oldIndexRetained", oldIndex,
                "activeIndex", rebuilt.index(), "verifiedDocuments", rebuilt.verified(), "pendingCount", 0,
                "failedQueueCount", 0));
    }

    private void verifyLateCommitRebuild(Actor actor) throws Exception {
        Actor independent = actor("earlier-commit");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong lowId = new AtomicLong();
        try {
            var writer = executor.submit(() -> transaction.executeWithoutResult(status -> {
                lowId.set(create(actor, "M2LateCommitNeedle", "M2LateCommitNeedle body"));
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("writer timeout"); }
                } catch (InterruptedException failure) {
                    java.lang.Thread.currentThread().interrupt();
                    throw new IllegalStateException("writer interrupted");
                }
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // Different authors avoid serializing on the user policy lock. Sharing the same
            // section also proves that normal content writers hold a shared section lock.
            long higherId = create(new Actor(independent.user(), actor.section()),
                    "M2EarlierCommitNeedle", "M2EarlierCommitNeedle body");
            assertThat(higherId).isGreaterThan(lowId.get());
            var rebuilding = executor.submit(rebuild::rebuild);
            java.lang.Thread.sleep(150);
            assertThat(rebuilding.isDone()).isFalse();
            release.countDown();
            writer.get(30, TimeUnit.SECONDS);
            var result = rebuilding.get(60, TimeUnit.SECONDS);
            assertThat(index.read(result.index(), lowId.get())).contains(owner.read(lowId.get()).orElseThrow());
            assertThat(index.read(result.index(), higherId)).contains(owner.read(higherId).orElseThrow());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void publishPending() {
        for (int step = 0; step < 100; step++) {
            var next = outbox.claimOne();
            if (next.isEmpty()) { return; }
            var delivery = next.orElseThrow();
            publisher.publish(delivery.message());
            assertThat(outbox.markPublished(delivery)).isTrue();
        }
        throw new AssertionError("Unexpected content event backlog");
    }

    private void consumeAll() throws Exception {
        Connection connection = connections.createConnection();
        Channel channel = connection.createChannel(false);
        try {
            for (int count = 0; count < 100; count++) {
                GetResponse response = channel.basicGet(QUEUE, false);
                if (response == null) { return; }
                consumer.onMessage(springMessage(response), channel);
            }
            throw new AssertionError("Unexpected search queue backlog");
        } finally {
            channel.close();
            connection.close();
        }
    }

    private GetResponse awaitDelivery(Channel channel, String queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            GetResponse response = channel.basicGet(queue, false);
            if (response != null) { return response; }
            java.lang.Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected broker delivery did not arrive");
    }

    private Message springMessage(GetResponse response) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
        return new Message(response.getBody(), properties);
    }

    private ContentEventMessage message(ThreadSearchDocument document) {
        return new ContentEventMessage(document.eventId(), 1, "THREAD", document.id(), document.version(),
                document.eventType(), Instant.now(), null);
    }

    private void assertTombstone(long id, long version) {
        var document = index.read(ContentSearchIndex.ALIAS, id).orElseThrow();
        assertThat(document.version()).isEqualTo(version);
        assertThat(document.visible()).isFalse();
        assertThat(document.title()).isEmpty();
        assertThat(document.content()).isEmpty();
        assertThat(document.tags()).isEmpty();
    }

    private void declareTopology() {
        RabbitAdmin admin = new RabbitAdmin(connections);
        context.getBeansOfType(Exchange.class).values().forEach(admin::declareExchange);
        context.getBeansOfType(Queue.class).values().forEach(admin::declareQueue);
        context.getBeansOfType(Binding.class).values().forEach(admin::declareBinding);
    }

    private int queueCount(String queue) throws Exception {
        Connection connection = connections.createConnection();
        Channel channel = connection.createChannel(false);
        try { return channel.queueDeclarePassive(queue).getMessageCount(); }
        finally { channel.close(); connection.close(); }
    }

    private String takeQueueBody(String queue) throws Exception {
        Connection connection = connections.createConnection();
        Channel channel = connection.createChannel(false);
        try {
            GetResponse response = awaitDelivery(channel, queue);
            channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
            return new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            channel.close();
            connection.close();
        }
    }

    private int awaitQueueAtLeast(String queue, int minimum) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            int count = queueCount(queue);
            if (count >= minimum) { return count; }
            java.lang.Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected durable queue count did not arrive");
    }

    private Actor actor(String suffix) {
        User user = new User();
        user.setUsername("m2-runtime-" + suffix);
        user.setEmail("m2-" + suffix + "@example.test");
        user.setPhone("139" + Math.abs(suffix.hashCode()));
        user.setPasswordHash("runtime-test-hash");
        long userId = users.saveAndFlush(user).getId();
        Section section = new Section();
        section.setSlug("m2-runtime-" + suffix);
        section.setName("M2 runtime " + suffix);
        return new Actor(userId, sections.saveAndFlush(section).getId());
    }

    private long create(Actor actor, String title, String body) {
        CreateThreadRequest request = new CreateThreadRequest();
        request.setSectionId(actor.section());
        request.setTitle(title);
        request.setContent(body);
        return threads.create(actor.user(), request).getId();
    }

    private void phase(String expected) {
        assertThat(System.getenv("CONTENT_SEARCH_TEST_PHASE")).isEqualTo(expected);
    }

    private void evidence(String phase, Map<String, Object> values) throws Exception {
        Path output = Path.of(System.getenv("CONTENT_SEARCH_TEST_EVIDENCE_DIRECTORY")).toAbsolutePath().normalize();
        Path root = Path.of("").toAbsolutePath().normalize();
        if (root.getFileName().toString().equals("backend")) { root = root.getParent(); }
        assertThat(output.startsWith(root.resolve("outputs"))).isTrue();
        Files.createDirectories(output);
        mapper.writeValue(output.resolve("readback-" + phase + ".json").toFile(), values);
    }

    private record Actor(long user, long section) { }
}
