package com.example.demo.threads.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.search.service.ThreadSearchService;
import com.example.demo.sections.entity.Section;
import com.example.demo.tags.entity.Tag;
import com.example.demo.tags.entity.ThreadTag;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.entity.ThreadLike;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Import(SqlStatementCountingConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:thread_search_database_query_cost;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
class ThreadSearchDatabaseQueryCostIntegrationTest {
    private static final int VISIBLE_THREAD_COUNT = 10;
    private static final List<Integer> INDEX_ORDER = List.of(4, 9, 1, 7, 2, 8, 0, 6, 3, 5);

    @Autowired
    private ThreadSearchProjectionService projections;

    @Autowired
    private ThreadSearchService searchService;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ContentSearchIndex index;

    @MockitoBean
    private ContentWriteGateService control;

    @Autowired
    private SqlStatementCountingConfiguration.PreparedStatementCounter preparedStatementCounter;

    private Long sectionId;
    private Long authorId;
    private final List<ThreadFixture> fixtures = new ArrayList<>();
    private Statistics statistics;

    @BeforeEach
    void createFixture() {
        Section section = new Section();
        section.setName("Thread search section");
        section.setSlug("thread-search-section");
        entityManager.persist(section);

        User author = new User();
        author.setUsername("thread-search-author");
        author.setEmail("thread-search-author@example.com");
        author.setPhone("13900000002");
        author.setPasswordHash("test-only-hash");
        author.setCreatedAt(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        entityManager.persist(author);

        UserProfile profile = new UserProfile();
        profile.setUserId(author.getId());
        profile.setNickname("搜索作者");
        profile.setAvatarUrl("/uploads/thread-search-author.png");
        entityManager.persist(profile);
        entityManager.flush();
        sectionId = section.getId();
        authorId = author.getId();

        Tag alpha = tag("alpha");
        Tag beta = tag("beta");
        entityManager.flush();

        fixtures.clear();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < VISIBLE_THREAD_COUNT; index++) {
            Thread thread = new Thread();
            thread.setSectionId(sectionId);
            thread.setAuthorId(authorId);
            thread.setTitle("batch-thread-" + index);
            thread.setContentMd("batch-search-content-" + index);
            thread.setStatus("NORMAL");
            thread.setCreatedAt(start.plusSeconds(index));
            thread.setUpdatedAt(start.plusSeconds(index));
            entityManager.persist(thread);
            entityManager.flush();

            int likeCount = index + 1;
            for (int likeIndex = 0; likeIndex < likeCount; likeIndex++) {
                ThreadLike like = new ThreadLike();
                like.setThreadId(thread.getId());
                like.setUserId(20_000L + index * 100L + likeIndex);
                entityManager.persist(like);
            }

            entityManager.persist(threadTag(thread.getId(), alpha.getId()));
            entityManager.persist(threadTag(thread.getId(), beta.getId()));
            fixtures.add(new ThreadFixture(thread.getId(), index, likeCount));
        }

        Thread hidden = new Thread();
        hidden.setSectionId(sectionId);
        hidden.setAuthorId(authorId);
        hidden.setTitle("batch-hidden");
        hidden.setContentMd("batch-search-hidden-content");
        hidden.setStatus("HIDDEN");
        hidden.setCreatedAt(start.plusSeconds(VISIBLE_THREAD_COUNT + 1L));
        hidden.setUpdatedAt(start.plusSeconds(VISIBLE_THREAD_COUNT + 1L));
        entityManager.persist(hidden);

        entityManager.flush();
        entityManager.clear();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        when(control.snapshot()).thenReturn(new ContentWriteGateService.Snapshot(
                "tongpin-threads-vtest", true, false, null));
        when(index.activeIndex()).thenReturn(Optional.of("tongpin-threads-vtest"));
    }

    @Test
    void databaseSearch_returns_visible_normal_ordered_rows_and_logs_query_cost() {
        for (int size : List.of(1, VISIBLE_THREAD_COUNT)) {
            QueryCounts counts = measure(() -> {
                var result = projections.databaseSearch(" batch ", null, sectionId, 1, size);
                assertThat(result.getTotalElements()).isEqualTo(VISIBLE_THREAD_COUNT);
                assertThat(result.getContent()).hasSize(size);
                assertThat(result.getContent()).extracting(ThreadDto::getTitle)
                        .containsExactlyElementsOf(expectedTitles(size));
                assertThat(result.getContent()).extracting(ThreadDto::getLikeCount)
                        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, size)
                                .mapToLong(index -> VISIBLE_THREAD_COUNT - index + 1L)
                                .boxed().toList());
                assertThat(result.getContent()).allSatisfy(dto -> {
                    assertThat(dto.getAuthorId()).isEqualTo(authorId);
                    assertThat(dto.getAuthorUsername()).isEqualTo("thread-search-author");
                    assertThat(dto.getAuthorNickname()).isEqualTo("搜索作者");
                    assertThat(dto.getSectionId()).isEqualTo(sectionId);
                    assertThat(dto.getStatus()).isEqualTo("NORMAL");
                    assertThat(dto.getLikeCount()).isPositive();
                    assertThat(dto.getTags()).containsExactly("alpha", "beta");
                });
            });

            System.out.println("THREAD_SEARCH_QUERY_COUNTS implementation=databaseSearch size=" + size
                    + " hibernate=" + counts.hibernate()
                    + " jdbcTags=" + counts.jdbcTags()
                    + " total=" + counts.total());
            assertThat(counts).isEqualTo(new QueryCounts(3L, 1L, 4L));
        }

        ThreadDto newest = projections.databaseSearch("batch", null, sectionId, 1, 1)
                .getContent().get(0);
        assertThat(newest.getTitle()).isEqualTo("batch-thread-9");
        ThreadFixture fixture = fixtures.stream()
                .filter(item -> item.index() == 9)
                .findFirst()
                .orElseThrow();
        assertThat(newest.getLikeCount()).isEqualTo(fixture.likeCount());
    }

    @Test
    void indexedSearch_returns_visible_rows_and_logs_query_cost() {
        for (int size : List.of(1, VISIBLE_THREAD_COUNT)) {
            List<ThreadFixture> indexed = INDEX_ORDER.stream().limit(size).map(fixtures::get).toList();
            List<ContentSearchIndex.SearchHit> hits = indexed.stream()
                    .map(fixture -> new ContentSearchIndex.SearchHit(fixture.id(), 0L))
                    .toList();
            when(index.search(ContentSearchIndex.ALIAS, "batch", null, sectionId, 1, size))
                    .thenReturn(new ContentSearchIndex.SearchPage(hits, VISIBLE_THREAD_COUNT));
            var indexedRequests = metrics.counter("content.search.requests", "source", "elasticsearch");
            double requestCountBefore = indexedRequests.count();

            QueryCounts counts = measure(() -> {
                var result = searchService.list(" batch ", null, sectionId, 1, size);
                assertThat(result.getTotalElements()).isEqualTo(VISIBLE_THREAD_COUNT);
                assertThat(result.getContent()).hasSize(size);
                assertThat(result.getContent()).extracting(ThreadDto::getId)
                        .containsExactlyElementsOf(indexed.stream().map(ThreadFixture::id).toList());
                assertThat(result.getContent()).extracting(ThreadDto::getLikeCount)
                        .containsExactlyElementsOf(indexed.stream().map(ThreadFixture::likeCount).toList());
                assertThat(result.getContent()).allSatisfy(dto -> {
                    assertThat(dto.getAuthorId()).isEqualTo(authorId);
                    assertThat(dto.getAuthorUsername()).isEqualTo("thread-search-author");
                    assertThat(dto.getAuthorNickname()).isEqualTo("搜索作者");
                    assertThat(dto.getSectionId()).isEqualTo(sectionId);
                    assertThat(dto.getStatus()).isEqualTo("NORMAL");
                    assertThat(dto.getLikeCount()).isPositive();
                    assertThat(dto.getTags()).containsExactly("alpha", "beta");
                });
            });

            System.out.println("THREAD_SEARCH_QUERY_COUNTS implementation=indexedSearch size=" + size
                    + " hibernate=" + counts.hibernate()
                    + " jdbcTags=" + counts.jdbcTags()
                    + " total=" + counts.total());
            assertThat(counts).isEqualTo(new QueryCounts(3L, 1L, 4L));
            assertThat(indexedRequests.count()).isEqualTo(requestCountBefore + 1);
        }
    }

    private List<String> expectedTitles(int size) {
        return java.util.stream.IntStream.iterate(VISIBLE_THREAD_COUNT - 1, index -> index >= 0, index -> index - 1)
                .limit(size)
                .mapToObj(index -> "batch-thread-" + index)
                .toList();
    }

    private QueryCounts measure(Runnable operation) {
        entityManager.clear();
        statistics.clear();
        preparedStatementCounter.reset();
        operation.run();
        long hibernate = statistics.getPrepareStatementCount();
        long total = preparedStatementCounter.count();
        long jdbcTags = total - hibernate;
        return new QueryCounts(hibernate, jdbcTags, total);
    }

    private Tag tag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType("thread");
        entityManager.persist(tag);
        return tag;
    }

    private ThreadTag threadTag(Long threadId, Long tagId) {
        ThreadTag threadTag = new ThreadTag();
        threadTag.setThreadId(threadId);
        threadTag.setTagId(tagId);
        return threadTag;
    }

    private record ThreadFixture(Long id, int index, long likeCount) {
    }

    private record QueryCounts(long hibernate, long jdbcTags, long total) {
    }
}
