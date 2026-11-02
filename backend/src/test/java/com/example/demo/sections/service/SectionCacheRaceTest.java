package com.example.demo.sections.service;

import com.example.demo.common.RedisKeys;
import com.example.demo.governance.dto.SectionRequest;
import com.example.demo.governance.entity.CommunityState;
import com.example.demo.governance.repo.CommunityAdminRoleRepository;
import com.example.demo.governance.repo.CommunityAuditRepository;
import com.example.demo.governance.repo.CommunityModeratorScopeRepository;
import com.example.demo.governance.repo.CommunityPostingRestrictionRepository;
import com.example.demo.governance.repo.CommunityStateRepository;
import com.example.demo.governance.repo.SectionGovernanceRepository;
import com.example.demo.governance.service.GovernanceService;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the cache generation boundary that closes the read/invalidating-write race.
 * This is a service-level test; it does not claim database, Redis-server, or HTTP proof.
 */
class SectionCacheRaceTest {

    private static final String INITIAL_GENERATION = "11111111-1111-1111-1111-111111111111";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SectionRepository sectionRepository = mock(SectionRepository.class);
    private final SectionGovernanceRepository sectionGovernanceRepository = mock(SectionGovernanceRepository.class);
    private final CommunityStateRepository stateRepository = mock(CommunityStateRepository.class);
    private final CommunityAdminRoleRepository adminRoleRepository = mock(CommunityAdminRoleRepository.class);
    private final CommunityModeratorScopeRepository moderatorScopeRepository = mock(CommunityModeratorScopeRepository.class);
    private final CommunityPostingRestrictionRepository restrictionRepository = mock(CommunityPostingRestrictionRepository.class);
    private final CommunityAuditRepository auditRepository = mock(CommunityAuditRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
    private final Map<String, String> entries = new ConcurrentHashMap<>();
    private GovernanceService governance;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(values.get(anyString())).thenAnswer(invocation -> entries.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            entries.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString());
        doAnswer(invocation -> {
            entries.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any());
        when(stateRepository.findByIdForUpdate(CommunityState.SINGLETON_ID))
                .thenReturn(Optional.of(new CommunityState()));
        when(adminRoleRepository.existsById(7L)).thenReturn(true);
        when(sectionGovernanceRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(auditRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sectionRepository.save(any(Section.class))).thenAnswer(invocation -> {
            Section section = invocation.getArgument(0);
            section.setId(11L);
            if (section.getCreatedAt() == null) {
                section.setCreatedAt(Instant.now());
            }
            return section;
        });
        governance = new GovernanceService(userRepository, sectionRepository, sectionGovernanceRepository,
                stateRepository, adminRoleRepository, moderatorScopeRepository,
                restrictionRepository, auditRepository, redisProvider);
        entries.put(RedisKeys.sectionsListGeneration(), INITIAL_GENERATION);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        entries.clear();
    }

    @Test
    void stale_read_after_commit_cannot_overwrite_the_new_generation_namespace() throws Exception {
        SectionService sections = new SectionService(sectionRepository, redis, null);
        CountDownLatch staleQueryStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleQuery = new CountDownLatch(1);
        AtomicInteger repositoryCalls = new AtomicInteger();
        Page<Section> stalePage = page(section(11L, "Old", "old"));
        Page<Section> currentPage = page(section(11L, "New", "new"));
        when(sectionRepository.search(any(), any(PageRequest.class))).thenAnswer(invocation -> {
            if (repositoryCalls.getAndIncrement() == 0) {
                staleQueryStarted.countDown();
                assertThat(releaseStaleQuery.await(5, TimeUnit.SECONDS)).isTrue();
                return stalePage;
            }
            return currentPage;
        });
        TransactionSynchronizationManager.initSynchronization();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var staleRead = executor.submit(() -> sections.list(null, 1, 20));
            assertThat(staleQueryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            governance.createSection(7L, new SectionRequest("Tech", "tech", null, null, true, false, "create"));
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCommit();
            String currentGeneration = entries.get(RedisKeys.sectionsListGeneration());
            assertThat(currentGeneration).isNotEqualTo(INITIAL_GENERATION);

            assertThat(sections.list(null, 1, 20).getContent())
                    .extracting(Section::getName).containsExactly("New");
            String currentKey = RedisKeys.sectionsList(currentGeneration, null, 1, 20);
            assertThat(entries).containsKey(currentKey);

            releaseStaleQuery.countDown();
            staleRead.get(5, TimeUnit.SECONDS);

            assertThat(sections.list(null, 1, 20).getContent())
                    .extracting(Section::getName).containsExactly("New");
            assertThat(entries).containsKey(currentKey);
            assertThat(repositoryCalls).hasValue(2);
        } finally {
            releaseStaleQuery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void create_invalidates_only_after_commit_with_a_fresh_generation_token() {
        TransactionSynchronizationManager.initSynchronization();

        governance.createSection(7L, new SectionRequest("Tech", "tech", null, null, true, false, "create"));

        verify(values, never()).set(eq(RedisKeys.sectionsListGeneration()), anyString());
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCommit();

        String generation = entries.get(RedisKeys.sectionsListGeneration());
        assertThat(generation).isNotBlank();
        assertThat(UUID.fromString(generation).toString()).isEqualTo(generation);
        verify(values).set(eq(RedisKeys.sectionsListGeneration()), eq(generation));
    }

    @Test
    void rollback_does_not_invalidate_generation() {
        TransactionSynchronizationManager.initSynchronization();

        governance.createSection(7L, new SectionRequest("Tech", "tech", null, null, true, false, "create"));

        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(values, never()).set(eq(RedisKeys.sectionsListGeneration()), anyString());
        assertThat(entries.get(RedisKeys.sectionsListGeneration())).isEqualTo(INITIAL_GENERATION);
    }

    @Test
    void multiple_commits_publish_distinct_generation_tokens() {
        TransactionSynchronizationManager.initSynchronization();

        governance.createSection(7L, new SectionRequest("Tech", "tech", null, null, true, false, "create"));
        governance.createSection(7L, new SectionRequest("News", "news", null, null, true, false, "create"));

        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.get(0).afterCommit();
        String firstGeneration = entries.get(RedisKeys.sectionsListGeneration());
        synchronizations.get(1).afterCommit();
        String secondGeneration = entries.get(RedisKeys.sectionsListGeneration());

        assertThat(UUID.fromString(firstGeneration).toString()).isEqualTo(firstGeneration);
        assertThat(UUID.fromString(secondGeneration).toString()).isEqualTo(secondGeneration);
        assertThat(secondGeneration).isNotEqualTo(firstGeneration);
    }

    private Page<Section> page(Section section) {
        return new PageImpl<>(List.of(section), PageRequest.of(0, 20), 1);
    }

    private Section section(Long id, String name, String slug) {
        Section section = new Section();
        section.setId(id);
        section.setName(name);
        section.setSlug(slug);
        section.setVisible(true);
        section.setCreatedAt(Instant.now());
        return section;
    }
}
