package com.example.demo.sections.service;

import com.example.demo.common.RedisKeys;
import com.example.demo.governance.dto.SectionRequest;
import com.example.demo.governance.entity.CommunityAdminRole;
import com.example.demo.governance.entity.CommunityState;
import com.example.demo.governance.repo.CommunityAdminRoleRepository;
import com.example.demo.governance.repo.CommunityAuditRepository;
import com.example.demo.governance.repo.CommunityStateRepository;
import com.example.demo.governance.repo.SectionGovernanceRepository;
import com.example.demo.governance.service.GovernanceService;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Real Spring/JPA transaction evidence for section-cache generation boundaries.
 *
 * <p>The database is an isolated random H2 in-memory database. Redis is not
 * exercised: the Redis bean is a Mockito replacement backed by a concurrent
 * map, so these tests prove the application transaction and cache-generation
 * callback contract without claiming a Redis-server integration test.</p>
 */
@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisabledInAotMode
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SectionCacheTransactionH2Test {

    private static final Long ADMIN_ID = 9001L;
    private static final String INITIAL_GENERATION = "11111111-1111-1111-1111-111111111111";
    private static final String H2_URL = "jdbc:h2:mem:bluealbum_section_cache_tx_"
            + UUID.randomUUID().toString().replace("-", "")
            + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false";

    @Autowired
    private GovernanceService governance;

    @Autowired
    private SectionService sectionService;

    @Autowired
    private SectionRepository sectionRepository;

    @Autowired
    private SectionGovernanceRepository sectionGovernanceRepository;

    @Autowired
    private CommunityStateRepository stateRepository;

    @Autowired
    private CommunityAdminRoleRepository adminRoleRepository;

    @Autowired
    private CommunityAuditRepository auditRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private StringRedisTemplate redis;

    private final Map<String, String> redisEntries = new ConcurrentHashMap<>();
    private ValueOperations<String, String> values;

    @DynamicPropertySource
    static void isolatedH2(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> H2_URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void resetDatabaseAndRedis() {
        reset(redis);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(invocation -> redisEntries.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redisEntries.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString());
        doAnswer(invocation -> {
            redisEntries.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.setIfAbsent(anyString(), anyString())).thenAnswer(invocation ->
                redisEntries.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        when(redis.delete(anyString())).thenAnswer(invocation ->
                redisEntries.remove(invocation.getArgument(0)) != null);

        inNewTransaction().executeWithoutResult(status -> {
            auditRepository.deleteAllInBatch();
            adminRoleRepository.deleteAllInBatch();
            sectionRepository.deleteAllInBatch();
            stateRepository.deleteAllInBatch();

            CommunityState state = new CommunityState();
            state.setId(CommunityState.SINGLETON_ID);
            state.setBootstrapped(true);
            stateRepository.saveAndFlush(state);
            adminRoleRepository.saveAndFlush(new CommunityAdminRole(ADMIN_ID));
        });
        redisEntries.clear();
        redisEntries.put(RedisKeys.sectionsListGeneration(), INITIAL_GENERATION);
    }

    @Test
    void committed_create_changes_generation_and_section_query_reads_committed_database_state() {
        inNewTransaction().executeWithoutResult(status -> governance.createSection(
                ADMIN_ID, request("Committed section", "committed-section", "create")));

        String generation = generation();
        assertThat(generation).isNotEqualTo(INITIAL_GENERATION);
        assertValidGeneration(generation);

        Page<Section> result = readWithoutTransaction();
        assertThat(result.getContent()).extracting(Section::getName)
                .containsExactly("Committed section");
        assertThat(sectionGovernanceRepository.findBySlug("committed-section")).isPresent();
        assertThat(redisEntries).containsKey(RedisKeys.sectionsList(generation, null, 1, 20));
    }

    @Test
    void committed_update_changes_generation_and_query_reads_new_database_state() {
        Long sectionId = seedSection("Before update", "transaction-target");

        Page<Section> oldResult = readWithoutTransaction();
        assertThat(oldResult.getContent()).extracting(Section::getName)
                .containsExactly("Before update");
        assertThat(redisEntries).containsKey(
                RedisKeys.sectionsList(INITIAL_GENERATION, null, 1, 20));

        inNewTransaction().executeWithoutResult(status -> governance.updateSection(
                ADMIN_ID, sectionId,
                request("After update", "transaction-target", "update")));

        String generation = generation();
        assertThat(generation).isNotEqualTo(INITIAL_GENERATION);
        assertValidGeneration(generation);

        Page<Section> result = readWithoutTransaction();
        assertThat(result.getContent()).extracting(Section::getName)
                .containsExactly("After update");
        Section stored = readSection(sectionId).orElseThrow();
        assertThat(stored.getName()).isEqualTo("After update");
        assertThat(redisEntries).containsKey(RedisKeys.sectionsList(generation, null, 1, 20));
    }

    @Test
    void rolled_back_update_keeps_generation_and_database_state() {
        Long sectionId = seedSection("Before rollback", "rollback-target");
        readWithoutTransaction();
        assertThat(generation()).isEqualTo(INITIAL_GENERATION);

        assertThatThrownBy(() -> inNewTransaction().executeWithoutResult(status -> {
            governance.updateSection(ADMIN_ID, sectionId,
                    request("Should roll back", "rollback-target", "rollback"));
            throw new IllegalStateException("force transaction rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force transaction rollback");

        assertThat(generation()).isEqualTo(INITIAL_GENERATION);
        Section stored = readSection(sectionId).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Before rollback");
        assertThat(readWithoutTransaction().getContent()).extracting(Section::getName)
                .containsExactly("Before rollback");
        verify(values, never()).set(eq(RedisKeys.sectionsListGeneration()), anyString());
    }

    @Test
    void rolled_back_create_keeps_generation_and_does_not_leave_a_database_row() {
        assertThatThrownBy(() -> inNewTransaction().executeWithoutResult(status -> {
            governance.createSection(ADMIN_ID,
                    request("Transient section", "transient-section", "rollback"));
            throw new IllegalStateException("force create rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force create rollback");

        assertThat(generation()).isEqualTo(INITIAL_GENERATION);
        assertThat(sectionGovernanceRepository.findBySlug("transient-section")).isEmpty();
        assertThat(readWithoutTransaction().getContent()).isEmpty();
        verify(values, never()).set(eq(RedisKeys.sectionsListGeneration()), anyString());
    }

    private TransactionTemplate inNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private Page<Section> readWithoutTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return template.execute(status -> sectionService.list(null, 1, 20));
    }

    private Optional<Section> readSection(Long sectionId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return template.execute(status -> sectionRepository.findById(sectionId));
    }

    private Long seedSection(String name, String slug) {
        return inNewTransaction().execute(status -> {
            Section section = new Section();
            section.setName(name);
            section.setSlug(slug);
            section.setVisible(true);
            section.setArchived(false);
            section.setCreatedAt(Instant.now());
            return sectionRepository.saveAndFlush(section).getId();
        });
    }

    private String generation() {
        return redisEntries.get(RedisKeys.sectionsListGeneration());
    }

    private void assertValidGeneration(String generation) {
        assertThat(UUID.fromString(generation).toString()).isEqualTo(generation);
    }

    private SectionRequest request(String name, String slug, String reason) {
        return new SectionRequest(name, slug, null, null, true, false, reason);
    }
}
