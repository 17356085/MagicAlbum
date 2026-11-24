package com.example.demo.threads.service;

import com.example.demo.Demo1Application;
import com.example.demo.threads.dto.ThreadFeedView;
import com.example.demo.threads.repo.ThreadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit-only MySQL 8 acceptance for the current following-feed query.
 *
 * <p>The class deliberately does not match the normal Surefire test includes.
 * Run it explicitly with {@code -Dtest=FollowingFeedCostAcceptance} and the
 * five {@code FEED_CHECK_*} environment variables. The test owns only an
 * isolated, caller-supplied database and writes sanitized JSON evidence to the
 * caller-supplied path.</p>
 *
 * <p>This is a bounded query/correctness fixture, not a production capacity
 * claim. Repository timings include the current page projection and total
 * count. The JDBC measurements and plans use the exact current status/follow
 * predicates and ordering without changing the public API.</p>
 */
@SpringBootTest(classes = Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/mysql",
        "spring.jpa.show-sql=false",
        "spring.main.web-application-type=none",
        "spring.data.redis.repositories.enabled=false",
        "app.rabbit.enabled=false",
        "app.content.events.enabled=false",
        "app.search.enabled=false",
        "app.notifications.client.enabled=false",
        "ai.provider=mock"
})
class FollowingFeedCostAcceptance {

    private static final String URL_ENV = "FEED_CHECK_MYSQL_URL";
    private static final String DATABASE_ENV = "FEED_CHECK_DATABASE";
    private static final String USER_ENV = "FEED_CHECK_USER";
    private static final String PASSWORD_ENV = "FEED_CHECK_PASSWORD";
    private static final String OUTPUT_ENV = "FEED_CHECK_OUTPUT";

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:(?<port>[0-9]{1,5})/"
                    + "(?<database>ba_feed_runtime_[0-9a-f]{12})(?:\\?[^\\s#]*)?$");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("ba_feed_runtime_[0-9a-f]{12}");

    private static final int AUTHOR_COUNT = 128;
    private static final int THREADS_PER_AUTHOR = 160;
    private static final int SHALLOW_FOLLOW_COUNT = 8;
    private static final int DEEP_PAGE_ZERO_BASED = 500;
    private static final int PAGE_SIZE = 20;
    private static final int WARMUP_SAMPLES = 2;
    private static final int MEASURED_SAMPLES = 8;
    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TIE_INSTANT = Instant.parse("2026-12-01T00:00:00Z");

    /**
     * Environment and path validation happens while the test class is loaded,
     * before Spring creates the application context.
     */
    private static final Config CONFIG = Config.fromEnvironment();

    private static final String THREAD_INSERT = """
            INSERT INTO threads(section_id, author_id, title, content_md, spoiler, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, FALSE, ?, ?, ?)
            """;
    private static final String KEYSET_PAGE = """
            SELECT t.id, t.author_id, t.created_at
            FROM threads t
            WHERE t.status = 'NORMAL'
              AND EXISTS (
                  SELECT 1 FROM user_follows uf
                  WHERE uf.follower_id = ? AND uf.following_id = t.author_id
              )
              AND (t.created_at < ? OR (t.created_at = ? AND t.id < ?))
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT ?
            """;

    @Autowired
    private ThreadRepository threadRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void isolatedMySqlProperties(DynamicPropertyRegistry registry) {
        // This read-only connection check occurs before the Spring application
        // context is refreshed and before the test can seed synthetic rows.
        verifyConfiguredDatabase(CONFIG);
        registry.add("spring.datasource.url", CONFIG::url);
        registry.add("spring.datasource.username", CONFIG::user);
        registry.add("spring.datasource.password", CONFIG::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    @Test
    void measuresCurrentFeedCostAndOffsetMutationBoundary() throws Exception {
        MysqlInfo mysql = verifySelectedDatabase();
        Fixture fixture = seedFixture();

        FeedSnapshot shallowPage = repositorySnapshot(fixture.shallowViewerId(), 0, PAGE_SIZE);
        FeedSnapshot shallowPageRepeat = repositorySnapshot(fixture.shallowViewerId(), 0, PAGE_SIZE);
        FeedSnapshot deepPageOne = repositorySnapshot(fixture.deepViewerId(), 0, PAGE_SIZE);
        FeedSnapshot deepPageOneRepeat = repositorySnapshot(fixture.deepViewerId(), 0, PAGE_SIZE);
        FeedSnapshot deepPageTwoBefore = repositorySnapshot(
                fixture.deepViewerId(), 1, PAGE_SIZE);

        assertThat(shallowPage.total()).isEqualTo(fixture.expectedShallowTotal());
        assertThat(deepPageOne.total()).isEqualTo(fixture.expectedDeepTotal());
        assertThat(shallowPage.ids()).containsExactlyElementsOf(shallowPageRepeat.ids());
        assertThat(deepPageOne.ids()).containsExactlyElementsOf(deepPageOneRepeat.ids());

        // The first five rows of every author share the same timestamp, so a
        // full first page is a direct tie-ordering check for the current query.
        assertThat(deepPageOne.rows()).allSatisfy(row -> assertThat(row.createdAt()).isEqualTo(TIE_INSTANT));
        assertStrictlyDescending(deepPageOne.ids());

        Benchmark shallowBenchmark = benchmark(
                "shallow", fixture.shallowViewerId(), 0, fixture.expectedShallowTotal());
        Benchmark deepBenchmark = benchmark(
                "deep", fixture.deepViewerId(), DEEP_PAGE_ZERO_BASED, fixture.expectedDeepTotal());
        Map<String, Object> deepKeyset = benchmarkDeepKeyset(fixture.deepViewerId());
        PlanEvidence plans = capturePlans(fixture);

        jdbc.update(THREAD_INSERT,
                fixture.sectionId(), fixture.authorIds().get(0),
                "feed-mutation-insert", "feed-mutation-insert", "NORMAL",
                Timestamp.from(TIE_INSTANT.plusSeconds(1)), Timestamp.from(TIE_INSTANT.plusSeconds(1)));

        FeedSnapshot deepPageTwoAfterInsert = repositorySnapshot(
                fixture.deepViewerId(), 1, PAGE_SIZE);
        Set<Long> offsetDuplicateIds = intersection(deepPageOne.ids(), deepPageTwoAfterInsert.ids());
        assertThat(offsetDuplicateIds)
                .as("an insert before the offset page boundary must demonstrate the current duplicate risk")
                .isNotEmpty();

        Row cursor = deepPageOne.rows().get(deepPageOne.rows().size() - 1);
        List<Row> keysetBeforeMutation = keysetPage(
                fixture.deepViewerId(), cursor.createdAt(), cursor.id(), PAGE_SIZE);
        assertThat(keysetBeforeMutation.stream().map(Row::id).toList())
                .as("the isolated keyset predicate should preserve the original next page")
                .containsExactlyElementsOf(deepPageTwoBefore.ids());
        assertThat(intersection(deepPageOne.ids(), keysetBeforeMutation.stream().map(Row::id).toList()))
                .as("the isolated keyset predicate should avoid the offset duplicate")
                .isEmpty();

        Row hidden = deepPageTwoBefore.rows().get(0);
        Row deleted = deepPageTwoBefore.rows().get(1);
        Row unfollowed = deepPageTwoBefore.rows().stream()
                .filter(row -> row.authorId() != hidden.authorId())
                .filter(row -> row.authorId() != deleted.authorId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fixture page needs another author for the unfollow check"));

        jdbc.update("UPDATE threads SET status='HIDDEN' WHERE id=?", hidden.id());
        jdbc.update("UPDATE threads SET status='DELETED' WHERE id=?", deleted.id());
        jdbc.update("DELETE FROM user_follows WHERE follower_id=? AND following_id=?",
                fixture.deepViewerId(), unfollowed.authorId());

        List<Row> keysetAfterMutation = keysetPage(
                fixture.deepViewerId(), cursor.createdAt(), cursor.id(), PAGE_SIZE);
        List<Long> keysetAfterIds = keysetAfterMutation.stream().map(Row::id).toList();
        assertThat(keysetAfterIds).doesNotContain(hidden.id(), deleted.id());
        assertThat(keysetAfterMutation).allSatisfy(row ->
                assertThat(row.authorId()).isNotEqualTo(unfollowed.authorId()));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("test", "FollowingFeedCostAcceptance");
        evidence.put("fixture", fixture.toEvidence());
        evidence.put("mysql", Map.of(
                "product", mysql.product(),
                "version", mysql.version(),
                "database", mysql.database()));
        evidence.put("repositoryMeasurements", List.of(
                shallowBenchmark.toEvidence(), deepBenchmark.toEvidence()));
        evidence.put("matchedDeepKeyset", deepKeyset);
        evidence.put("plans", plans.toEvidence());
        evidence.put("mutationFlags", Map.of(
                "staticTieOrderingStable", deepPageOne.ids().equals(deepPageOneRepeat.ids()),
                "offsetDuplicateObserved", !offsetDuplicateIds.isEmpty(),
                "offsetDuplicateIds", offsetDuplicateIds.stream().sorted().toList(),
                "keysetAvoidedDuplicate", intersection(
                        deepPageOne.ids(), keysetBeforeMutation.stream().map(Row::id).toList()).isEmpty(),
                "keysetDropsHidden", !keysetAfterIds.contains(hidden.id()),
                "keysetDropsDeleted", !keysetAfterIds.contains(deleted.id()),
                "keysetDropsUnfollowed", keysetAfterMutation.stream()
                        .noneMatch(row -> row.authorId() == unfollowed.authorId()),
                "apiChanged", false));
        evidence.put("credentialsIncluded", false);

        writeEvidence(evidence);
        System.out.printf(Locale.ROOT,
                "FOLLOWING_FEED_ACCEPTANCE threads=%d shallowTotal=%d deepTotal=%d "
                        + "offsetDuplicate=%s keysetMutationChecks=true%n",
                fixture.threadCount(), fixture.expectedShallowTotal(), fixture.expectedDeepTotal(),
                !offsetDuplicateIds.isEmpty());
    }

    private Fixture seedFixture() {
        assertEmptyFixture();

        jdbc.update("INSERT INTO sections(name, slug, description, rules, visible) VALUES (?, ?, ?, ?, TRUE)",
                "Feed acceptance section", "feed-acceptance-section", "Feed acceptance fixture", "");
        long sectionId = jdbc.queryForObject(
                "SELECT id FROM sections WHERE slug=?", Long.class, "feed-acceptance-section");

        List<UserSeed> users = new ArrayList<>();
        users.add(new UserSeed("feed-viewer-shallow", 0));
        users.add(new UserSeed("feed-viewer-deep", 1));
        for (int index = 0; index < AUTHOR_COUNT; index++) {
            users.add(new UserSeed(String.format(Locale.ROOT, "feed-author-%03d", index), index + 2));
        }
        for (UserSeed user : users) {
            jdbc.update("""
                    INSERT INTO users(username, email, phone, password_hash, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, user.username(), user.username() + "@feed.acceptance.invalid",
                    String.format(Locale.ROOT, "13%09d", 700000000L + user.ordinal()),
                    "feed-acceptance-password-hash", Timestamp.from(BASE_INSTANT));
        }

        Map<String, Long> idsByUsername = jdbc.query(
                "SELECT id, username FROM users WHERE username LIKE 'feed-%' ORDER BY username",
                (resultSet, rowNum) -> Map.entry(resultSet.getString("username"), resultSet.getLong("id")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
        long shallowViewerId = requireId(idsByUsername, "feed-viewer-shallow");
        long deepViewerId = requireId(idsByUsername, "feed-viewer-deep");
        List<Long> authorIds = new ArrayList<>();
        for (int index = 0; index < AUTHOR_COUNT; index++) {
            authorIds.add(requireId(idsByUsername, String.format(Locale.ROOT, "feed-author-%03d", index)));
        }

        for (Map.Entry<String, Long> user : idsByUsername.entrySet()) {
            jdbc.update("INSERT INTO user_profiles(user_id, nickname, avatar_url) VALUES (?, ?, ?)",
                    user.getValue(), user.getKey(), "/uploads/feed-acceptance.png");
        }

        for (int index = 0; index < authorIds.size(); index++) {
            Long authorId = authorIds.get(index);
            if (index < SHALLOW_FOLLOW_COUNT) {
                insertFollow(shallowViewerId, authorId, index);
            }
            insertFollow(deepViewerId, authorId, index);
        }

        List<Object[]> batch = new ArrayList<>(1000);
        for (int authorIndex = 0; authorIndex < AUTHOR_COUNT; authorIndex++) {
            for (int threadIndex = 0; threadIndex < THREADS_PER_AUTHOR; threadIndex++) {
                String status = threadIndex == THREADS_PER_AUTHOR - 2
                        ? "HIDDEN" : threadIndex == THREADS_PER_AUTHOR - 1 ? "DELETED" : "NORMAL";
                Instant createdAt = threadIndex < 5
                        ? TIE_INSTANT
                        : BASE_INSTANT.plusSeconds((long) authorIndex * THREADS_PER_AUTHOR + threadIndex);
                batch.add(new Object[]{
                        sectionId,
                        authorIds.get(authorIndex),
                        String.format(Locale.ROOT, "feed-thread-%03d-%03d", authorIndex, threadIndex),
                        String.format(Locale.ROOT, "deterministic feed fixture thread %d/%d", authorIndex, threadIndex),
                        status,
                        Timestamp.from(createdAt),
                        Timestamp.from(createdAt)
                });
                if (batch.size() == 1000) {
                    jdbc.batchUpdate(THREAD_INSERT, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(THREAD_INSERT, batch);
        }

        long threadCount = jdbc.queryForObject("SELECT COUNT(*) FROM threads", Long.class);
        assertThat(threadCount).isEqualTo((long) AUTHOR_COUNT * THREADS_PER_AUTHOR);
        return new Fixture(sectionId, shallowViewerId, deepViewerId, authorIds, threadCount,
                (long) SHALLOW_FOLLOW_COUNT * (THREADS_PER_AUTHOR - 2L),
                (long) AUTHOR_COUNT * (THREADS_PER_AUTHOR - 2L));
    }

    private void insertFollow(long followerId, long followingId, int order) {
        jdbc.update("""
                INSERT INTO user_follows(follower_id, following_id, created_at)
                VALUES (?, ?, ?)
                """, followerId, followingId, Timestamp.from(BASE_INSTANT.plusSeconds(order)));
    }

    private void assertEmptyFixture() {
        // Flyway intentionally seeds system sections. The user-owned tables must start empty.
        for (String table : List.of("users", "threads", "user_follows")) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            assertThat(count).as("isolated acceptance table must be empty before seeding: " + table).isZero();
        }
    }

    private Benchmark benchmark(String label, long viewerId, int pageZeroBased, long expectedTotal) {
        for (int index = 0; index < WARMUP_SAMPLES; index++) {
            repositoryTimedSnapshot(viewerId, pageZeroBased, PAGE_SIZE);
            rawPage(viewerId, PAGE_SIZE, pageZeroBased * PAGE_SIZE);
            rawCount(viewerId);
        }

        List<Long> repositoryNanos = new ArrayList<>(MEASURED_SAMPLES);
        List<Long> rawPageNanos = new ArrayList<>(MEASURED_SAMPLES);
        List<Long> rawCountNanos = new ArrayList<>(MEASURED_SAMPLES);
        long observedTotal = -1L;
        int observedItems = -1;
        for (int index = 0; index < MEASURED_SAMPLES; index++) {
            TimedSnapshot repositorySample = repositoryTimedSnapshot(viewerId, pageZeroBased, PAGE_SIZE);
            repositoryNanos.add(repositorySample.elapsedNanos());
            observedTotal = observedTotal < 0 ? repositorySample.snapshot().total() : observedTotal;
            observedItems = observedItems < 0 ? repositorySample.snapshot().rows().size() : observedItems;
            assertThat(repositorySample.snapshot().total()).isEqualTo(observedTotal);
            assertThat(repositorySample.snapshot().rows()).hasSize(observedItems);

            TimedRaw rawPageSample = timedRawPage(viewerId, PAGE_SIZE, pageZeroBased * PAGE_SIZE);
            TimedRaw rawCountSample = timedRawCount(viewerId);
            rawPageNanos.add(rawPageSample.elapsedNanos());
            rawCountNanos.add(rawCountSample.elapsedNanos());
            assertThat(rawCountSample.value()).isEqualTo(observedTotal);
            assertThat(rawPageSample.rows()).containsExactlyElementsOf(repositorySample.snapshot().ids());
        }

        assertThat(observedTotal).isEqualTo(expectedTotal);
        return new Benchmark(label, pageZeroBased + 1, PAGE_SIZE, observedTotal, observedItems,
                stats(repositoryNanos), stats(rawPageNanos), stats(rawCountNanos));
    }

    private TimedSnapshot repositoryTimedSnapshot(long viewerId, int pageZeroBased, int pageSize) {
        long start = System.nanoTime();
        FeedSnapshot snapshot = repositorySnapshot(viewerId, pageZeroBased, pageSize);
        return new TimedSnapshot(System.nanoTime() - start, snapshot);
    }

    private FeedSnapshot repositorySnapshot(long viewerId, int pageZeroBased, int pageSize) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        return transaction.execute(status -> {
            entityManager.clear();
            Page<ThreadFeedView> page = threadRepository.findFollowingFeed(
                    viewerId, PageRequest.of(pageZeroBased, pageSize));
            List<Row> rows = page.getContent().stream()
                    .map(view -> new Row(view.getId(), view.getAuthorId(), view.getCreatedAt()))
                    .toList();
            return new FeedSnapshot(rows, page.getTotalElements());
        });
    }

    private TimedRaw timedRawPage(long viewerId, int pageSize, int offset) {
        long start = System.nanoTime();
        List<Long> rows = rawPage(viewerId, pageSize, offset);
        return new TimedRaw(System.nanoTime() - start, rows.size(), rows);
    }

    private TimedRaw timedRawCount(long viewerId) {
        long start = System.nanoTime();
        long count = rawCount(viewerId);
        return new TimedRaw(System.nanoTime() - start, count, List.of());
    }

    private List<Long> rawPage(long viewerId, int pageSize, int offset) {
        return jdbc.query(pageSql(viewerId, pageSize, offset),
                (resultSet, rowNum) -> resultSet.getLong(1));
    }

    private long rawCount(long viewerId) {
        return jdbc.queryForObject(countSql(viewerId), Long.class);
    }

    private List<Row> keysetPage(long viewerId, Instant createdAt, long id, int pageSize) {
        return jdbc.query(KEYSET_PAGE,
                (resultSet, rowNum) -> new Row(
                        resultSet.getLong("id"),
                        resultSet.getLong("author_id"),
                        resultSet.getTimestamp("created_at").toInstant()),
                viewerId, Timestamp.from(createdAt), Timestamp.from(createdAt), id, pageSize);
    }

    private Map<String, Object> benchmarkDeepKeyset(long viewerId) {
        Row cursor = repositorySnapshot(viewerId, DEEP_PAGE_ZERO_BASED - 1, PAGE_SIZE).rows().getLast();
        List<Long> expectedIds = repositorySnapshot(viewerId, DEEP_PAGE_ZERO_BASED, PAGE_SIZE).ids();
        List<Long> samples = new ArrayList<>(MEASURED_SAMPLES);
        for (int index = 0; index < WARMUP_SAMPLES + MEASURED_SAMPLES; index++) {
            long start = System.nanoTime();
            List<Row> page = keysetPage(viewerId, cursor.createdAt(), cursor.id(), PAGE_SIZE);
            long elapsed = System.nanoTime() - start;
            assertThat(page.stream().map(Row::id).toList()).containsExactlyElementsOf(expectedIds);
            if (index >= WARMUP_SAMPLES) {
                samples.add(elapsed);
            }
        }
        return Map.of("page", DEEP_PAGE_ZERO_BASED + 1, "pageSize", PAGE_SIZE, "sameOrderedIds", true,
                "excludesCursorLookupAndTotalCount", true, "prototypePage", stats(samples));
    }

    private PlanEvidence capturePlans(Fixture fixture) {
        return new PlanEvidence(
                explain(pageSql(fixture.shallowViewerId(), PAGE_SIZE, 0)),
                explain(countSql(fixture.shallowViewerId())),
                explain(pageSql(fixture.deepViewerId(), PAGE_SIZE, DEEP_PAGE_ZERO_BASED * PAGE_SIZE)),
                explain(countSql(fixture.deepViewerId())));
    }

    private Object explain(String sql) {
        String plan = jdbc.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class);
        try {
            return objectMapper.readTree(plan);
        } catch (IOException invalidJson) {
            throw new IllegalStateException("MySQL EXPLAIN did not return a JSON plan", invalidJson);
        }
    }

    private void writeEvidence(Map<String, Object> evidence) throws IOException {
        Files.createDirectories(CONFIG.output().toAbsolutePath().normalize().getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG.output().toFile(), evidence);
    }

    private MysqlInfo verifySelectedDatabase() {
        MysqlInfo info = jdbc.queryForObject("SELECT VERSION(), DATABASE()",
                (resultSet, rowNum) -> new MysqlInfo(
                        resultSet.getString(1), resultSet.getString(2)));
        assertThat(info.product()).isEqualTo("MySQL");
        assertThat(info.version()).startsWith("8.");
        assertThat(info.database()).isEqualTo(CONFIG.database());
        return info;
    }

    private static void verifyConfiguredDatabase(Config config) {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password());
             PreparedStatement databaseQuery = connection.prepareStatement("SELECT DATABASE(), VERSION()");
             ResultSet result = databaseQuery.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("MySQL preflight returned no selected database");
            }
            String selectedDatabase = result.getString(1);
            String version = result.getString(2);
            if (!config.database().equals(selectedDatabase)) {
                throw new IllegalStateException("MySQL preflight selected an unexpected database");
            }
            if (!version.startsWith("8.")) {
                throw new IllegalStateException("Feed acceptance requires MySQL 8");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("MySQL feed acceptance preflight failed", failure);
        }
    }

    private static String pageSql(long viewerId, int pageSize, int offset) {
        return String.format(Locale.ROOT, """
                SELECT t.id, t.author_id, t.created_at
                FROM threads t
                WHERE t.status = 'NORMAL'
                  AND EXISTS (
                      SELECT 1 FROM user_follows uf
                      WHERE uf.follower_id = %d AND uf.following_id = t.author_id
                  )
                ORDER BY t.created_at DESC, t.id DESC
                LIMIT %d OFFSET %d
                """, viewerId, pageSize, offset);
    }

    private static String countSql(long viewerId) {
        return String.format(Locale.ROOT, """
                SELECT COUNT(*)
                FROM threads t
                WHERE t.status = 'NORMAL'
                  AND EXISTS (
                      SELECT 1 FROM user_follows uf
                      WHERE uf.follower_id = %d AND uf.following_id = t.author_id
                  )
                """, viewerId);
    }

    private static Map<String, Object> stats(List<Long> nanos) {
        List<Long> sorted = nanos.stream().sorted().toList();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("samples", sorted.size());
        evidence.put("unit", "milliseconds");
        evidence.put("values", sorted.stream().map(FollowingFeedCostAcceptance::milliseconds).toList());
        evidence.put("p50", milliseconds(percentile(sorted, 0.50)));
        evidence.put("p95", milliseconds(percentile(sorted, 0.95)));
        evidence.put("p99", milliseconds(percentile(sorted, 0.99)));
        return evidence;
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private static double milliseconds(long nanos) {
        return Math.round(nanos / 1_000_000.0 * 100.0) / 100.0;
    }

    private static Set<Long> intersection(List<Long> left, List<Long> right) {
        Set<Long> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static void assertStrictlyDescending(List<Long> ids) {
        for (int index = 1; index < ids.size(); index++) {
            assertThat(ids.get(index - 1)).isGreaterThan(ids.get(index));
        }
    }

    private static long requireId(Map<String, Long> ids, String username) {
        Long id = ids.get(username);
        if (id == null) {
            throw new IllegalStateException("fixture user was not inserted: " + username);
        }
        return id;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required feed acceptance configuration is missing: " + name);
        }
        return value.trim();
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("backend"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the BlueAlbum repository root");
    }

    private record Config(String url, String database, String user, String password, Path output) {
        private static Config fromEnvironment() {
            String url = required(URL_ENV);
            String database = required(DATABASE_ENV);
            String user = required(USER_ENV);
            String password = required(PASSWORD_ENV);
            String outputValue = required(OUTPUT_ENV);
            Matcher matcher = URL_PATTERN.matcher(url);
            if (!matcher.matches()) {
                throw new IllegalStateException("FEED_CHECK_MYSQL_URL must use a 127.0.0.1 MySQL URL");
            }
            int port;
            try {
                port = Integer.parseInt(matcher.group("port"));
            } catch (NumberFormatException invalidPort) {
                throw new IllegalStateException("FEED_CHECK_MYSQL_URL has an invalid port", invalidPort);
            }
            if (port < 1024 || port > 65535) {
                throw new IllegalStateException("FEED_CHECK_MYSQL_URL must use an unprivileged loopback port");
            }
            if (!DATABASE_PATTERN.matcher(database).matches()
                    || !database.equals(matcher.group("database"))) {
                throw new IllegalStateException(
                        "FEED_CHECK_DATABASE and the JDBC URL must match ba_feed_runtime_[a-f0-9]{12}");
            }

            Path repository = repositoryRoot();
            Path output = Path.of(outputValue).toAbsolutePath().normalize();
            Path outputsRoot = repository.resolve("outputs").toAbsolutePath().normalize();
            if (!output.startsWith(outputsRoot) || output.equals(outputsRoot)) {
                throw new IllegalStateException("FEED_CHECK_OUTPUT must be a file under the repository outputs directory");
            }
            if (Files.exists(output) && Files.isDirectory(output)) {
                throw new IllegalStateException("FEED_CHECK_OUTPUT must name a JSON evidence file");
            }
            return new Config(url, database, user, password, output);
        }
    }

    private record MysqlInfo(String version, String database) {
        String product() {
            return "MySQL";
        }
    }

    private record UserSeed(String username, int ordinal) {
    }

    private record Fixture(long sectionId, long shallowViewerId, long deepViewerId,
                           List<Long> authorIds, long threadCount,
                           long expectedShallowTotal, long expectedDeepTotal) {
        Map<String, Object> toEvidence() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("threadCount", threadCount);
            evidence.put("authorCount", authorIds.size());
            evidence.put("threadsPerAuthor", THREADS_PER_AUTHOR);
            evidence.put("shallowFollowDegree", SHALLOW_FOLLOW_COUNT);
            evidence.put("deepFollowDegree", authorIds.size());
            evidence.put("expectedShallowNormalTotal", expectedShallowTotal);
            evidence.put("expectedDeepNormalTotal", expectedDeepTotal);
            evidence.put("hiddenThreads", authorIds.size());
            evidence.put("deletedThreads", authorIds.size());
            evidence.put("tieRowsPerAuthor", 5);
            evidence.put("replyLikeFixture", false);
            evidence.put("sectionId", sectionId);
            return evidence;
        }
    }

    private record Row(long id, long authorId, Instant createdAt) {
    }

    private record FeedSnapshot(List<Row> rows, long total) {
        List<Long> ids() {
            return rows.stream().map(Row::id).toList();
        }
    }

    private record TimedSnapshot(long elapsedNanos, FeedSnapshot snapshot) {
    }

    private record TimedRaw(long elapsedNanos, long value, List<Long> rows) {
    }

    private record Benchmark(String label, int pageOneBased, int pageSize, long total,
                             int itemCount, Map<String, Object> repository,
                             Map<String, Object> page, Map<String, Object> count) {
        Map<String, Object> toEvidence() {
            return Map.of(
                    "label", label,
                    "page", pageOneBased,
                    "pageSize", pageSize,
                    "total", total,
                    "itemCount", itemCount,
                    "repositoryPageAndCount", repository,
                    "equivalentPredicatePage", page,
                    "equivalentPredicateCount", count,
                    "warmupSamples", WARMUP_SAMPLES,
                    "measuredSamples", MEASURED_SAMPLES);
        }
    }

    private record PlanEvidence(Object shallowPage, Object shallowCount,
                                Object deepPage, Object deepCount) {
        Map<String, Object> toEvidence() {
            return Map.of(
                    "shallowEquivalentPredicatePage", shallowPage,
                    "shallowEquivalentPredicateCount", shallowCount,
                    "deepEquivalentPredicatePage", deepPage,
                    "deepEquivalentPredicateCount", deepCount);
        }
    }

}
