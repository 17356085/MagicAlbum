package com.example.demo.governance.service;

import com.example.demo.common.RedisCacheEvictor;
import com.example.demo.governance.dto.AuditView;
import com.example.demo.governance.dto.SectionRequest;
import com.example.demo.governance.dto.SectionView;
import com.example.demo.governance.dto.UserView;
import com.example.demo.governance.entity.CommunityAdminRole;
import com.example.demo.governance.entity.CommunityAudit;
import com.example.demo.governance.entity.CommunityModeratorScope;
import com.example.demo.governance.entity.CommunityPostingRestriction;
import com.example.demo.governance.entity.CommunityState;
import com.example.demo.governance.repo.CommunityAdminRoleRepository;
import com.example.demo.governance.repo.CommunityAuditRepository;
import com.example.demo.governance.repo.CommunityModeratorScopeRepository;
import com.example.demo.governance.repo.CommunityPostingRestrictionRepository;
import com.example.demo.governance.repo.CommunityStateRepository;
import com.example.demo.governance.repo.SectionGovernanceRepository;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Database-backed community governance. The service is deliberately the
 * authorization boundary used by content writes as well as governance HTTP
 * endpoints; no controller-local role cache is consulted.
 */
@Service
public class GovernanceService {
    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_QUERY_LENGTH = 128;
    private static final int MAX_TEXT_LENGTH = 10_000;
    private static final Pattern SECTION_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final UserRepository userRepository;
    private final SectionRepository sectionRepository;
    private final SectionGovernanceRepository sectionGovernanceRepository;
    private final CommunityStateRepository stateRepository;
    private final CommunityAdminRoleRepository adminRoleRepository;
    private final CommunityModeratorScopeRepository moderatorScopeRepository;
    private final CommunityPostingRestrictionRepository restrictionRepository;
    private final CommunityAuditRepository auditRepository;
    private StringRedisTemplate redisTemplate;

    /** Constructor retained for focused unit tests that do not need Redis. */
    public GovernanceService(UserRepository userRepository,
                             SectionRepository sectionRepository,
                             SectionGovernanceRepository sectionGovernanceRepository,
                             CommunityStateRepository stateRepository,
                             CommunityAdminRoleRepository adminRoleRepository,
                             CommunityModeratorScopeRepository moderatorScopeRepository,
                             CommunityPostingRestrictionRepository restrictionRepository,
                             CommunityAuditRepository auditRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.sectionRepository = Objects.requireNonNull(sectionRepository, "sectionRepository");
        this.sectionGovernanceRepository = Objects.requireNonNull(sectionGovernanceRepository,
                "sectionGovernanceRepository");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.adminRoleRepository = Objects.requireNonNull(adminRoleRepository, "adminRoleRepository");
        this.moderatorScopeRepository = Objects.requireNonNull(moderatorScopeRepository,
                "moderatorScopeRepository");
        this.restrictionRepository = Objects.requireNonNull(restrictionRepository, "restrictionRepository");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
    }

    @Autowired
    public GovernanceService(UserRepository userRepository,
                             SectionRepository sectionRepository,
                             SectionGovernanceRepository sectionGovernanceRepository,
                             CommunityStateRepository stateRepository,
                             CommunityAdminRoleRepository adminRoleRepository,
                             CommunityModeratorScopeRepository moderatorScopeRepository,
                             CommunityPostingRestrictionRepository restrictionRepository,
                             CommunityAuditRepository auditRepository,
                             ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(userRepository, sectionRepository, sectionGovernanceRepository, stateRepository,
                adminRoleRepository, moderatorScopeRepository, restrictionRepository, auditRepository);
        this.redisTemplate = redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
    }

    /**
     * Current access is read directly from the role, scope and restriction
     * tables. Expired restrictions are treated as inactive while retaining
     * the database row for auditability and future cleanup.
     */
    @Transactional(readOnly = true)
    public Access access(Long userId) {
        if (userId == null) {
            return new Access(false, List.of(), false, null);
        }
        boolean admin = adminRoleRepository.existsById(userId);
        List<Long> scopes = moderatorScopeRepository.findSectionIdsByUserId(userId);
        if (scopes == null) {
            scopes = List.of();
        } else {
            scopes = scopes.stream().filter(Objects::nonNull).distinct().sorted().toList();
        }
        CommunityPostingRestriction restriction = restrictionRepository.findById(userId).orElse(null);
        Instant until = restriction == null ? null : restriction.getRestrictedUntil();
        boolean restricted = restriction != null && (until == null || until.isAfter(Instant.now()));
        return new Access(admin, scopes, restricted, restricted ? until : null);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(Long userId) {
        return userId != null && adminRoleRepository.existsById(userId);
    }

    /** Requires a currently persisted admin role or at least one moderator scope. */
    @Transactional(readOnly = true)
    public void requireManager(Long actor) {
        if (actor == null) {
            throw failure(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Access current = access(actor);
        if (!current.admin() && current.moderatedSectionIds().isEmpty()) {
            throw failure(HttpStatus.FORBIDDEN, "Community manager access required");
        }
    }

    /** Login-only guard for the self access endpoint. */
    @Transactional(readOnly = true)
    public void requireManagerOrAuthenticated(Long actor) {
        if (actor == null) {
            throw failure(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    /** Requires either global admin access or a moderator scope for a section. */
    @Transactional(readOnly = true)
    public void requireScope(Long actor, Long sectionId) {
        if (actor == null) {
            throw failure(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (sectionId == null || sectionId <= 0) {
            throw failure(HttpStatus.NOT_FOUND, "Section not found");
        }
        if (isAdmin(actor)) {
            return;
        }
        if (!moderatorScopeRepository.existsByUserIdAndSectionId(actor, sectionId)) {
            throw failure(HttpStatus.FORBIDDEN, "Moderator scope required");
        }
    }

    /**
     * Locks the singleton policy row. Role changes and standalone audit writes
     * call this method so concurrent policy decisions serialize in the same
     * database transaction. The row is created for H2 create-drop tests when
     * Flyway has not seeded it yet.
     */
    @Transactional
    public void lockPolicy() {
        lockPolicyRow();
    }

    /**
     * Content write gate. The user row is locked before the section row as a
     * fixed lock order. This avoids stale restrictions and makes the 401/404/
     * 403 outcomes independent of controller state.
     */
    @Transactional
    public void assertCanWrite(Long actor, Long sectionId) {
        lockActor(actor);
        Section section = sectionId == null ? null
                : sectionGovernanceRepository.findByIdForWriting(sectionId).orElse(null);
        if (section == null) {
            throw failure(HttpStatus.NOT_FOUND, "Section not found");
        }
        CommunityPostingRestriction restriction = restrictionRepository.findById(actor).orElse(null);
        if (restriction != null) {
            Instant until = restriction.getRestrictedUntil();
            if (until == null || until.isAfter(Instant.now())) {
                throw failure(HttpStatus.FORBIDDEN, "Posting is restricted");
            }
        }
        if (readArchived(section)) {
            throw failure(HttpStatus.FORBIDDEN, "Section is archived");
        }
    }

    /** Serialize natural-key operations by actor without imposing posting restrictions. */
    @Transactional
    public void lockActor(Long actor) {
        if (actor == null) {
            throw failure(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        User user = userRepository.findByIdForInteraction(actor).orElse(null);
        if (user == null) {
            throw failure(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    /**
     * Bootstrap is intentionally one-way. Once the state row is marked, a
     * restart with the same configured email cannot resurrect a role that an
     * administrator later revoked.
     */
    @Transactional
    public void bootstrapAdmin(Long userId) {
        if (userId == null || userId <= 0) {
            throw failure(HttpStatus.BAD_REQUEST, "Invalid bootstrap user");
        }
        CommunityState state = lockPolicyRow();
        User user = userRepository.findByIdForInteraction(userId).orElse(null);
        if (user == null) {
            throw failure(HttpStatus.NOT_FOUND, "User not found");
        }
        if (state.isBootstrapped()) {
            return;
        }
        if (!adminRoleRepository.existsById(userId)) {
            adminRoleRepository.save(new CommunityAdminRole(userId));
            auditInternal(userId, "ADMIN_BOOTSTRAPPED", "USER", userId, "Configured initial administrator bootstrap");
        }
        state.setBootstrapped(true);
        stateRepository.save(state);
    }

    /** Adds an append-only governance event and serializes with policy changes. */
    @Transactional
    public CommunityAudit audit(Long actor,
                                String action,
                                String targetType,
                                Long targetId,
                                String reason) {
        lockPolicy();
        return auditInternal(actor, action, targetType, targetId, reason);
    }

    /** Trims and bounds user-supplied governance explanations. */
    public String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < 1 || normalized.length() > 500) {
            throw failure(HttpStatus.BAD_REQUEST, "reason must be between 1 and 500 characters");
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    public Page<UserView> listUsers(String query, int page, int size, Long actor) {
        requireAdmin(actor);
        String normalizedQuery = normalizeQuery(query);
        Page<User> users = userRepository.search(normalizedQuery, pageRequest(page, size));
        return users.map(this::toUserView);
    }

    @Transactional
    public UserView setAdmin(Long actor, Long targetUserId, Boolean enabled, String reason) {
        lockAndRequireManager(actor);
        if (enabled == null) {
            throw failure(HttpStatus.BAD_REQUEST, "enabled is required");
        }
        String normalizedReason = normalizeReason(reason);
        User target = lockUser(targetUserId);
        boolean currentlyAdmin = adminRoleRepository.existsById(targetUserId);
        if (enabled && !currentlyAdmin) {
            adminRoleRepository.save(new CommunityAdminRole(targetUserId));
        } else if (!enabled && currentlyAdmin) {
            if (adminRoleRepository.count() <= 1) {
                throw failure(HttpStatus.CONFLICT, "The last administrator cannot be revoked");
            }
            adminRoleRepository.deleteById(targetUserId);
        }
        auditInternal(actor, "ADMIN_ROLE_CHANGED", "USER", targetUserId, normalizedReason);
        return toUserView(target);
    }

    @Transactional
    public UserView setModeratorSections(Long actor,
                                         Long targetUserId,
                                         Collection<Long> requestedSectionIds,
                                         String reason) {
        lockAndRequireManager(actor);
        String normalizedReason = normalizeReason(reason);
        User target = lockUser(targetUserId);
        Set<Long> sectionIds = normalizeSectionIds(requestedSectionIds);
        if (!sectionIds.isEmpty()) {
            List<Section> sections = sectionRepository.findAllById(sectionIds);
            Set<Long> found = sections.stream().map(Section::getId).collect(java.util.stream.Collectors.toSet());
            if (found.size() != sectionIds.size()) {
                throw failure(HttpStatus.NOT_FOUND, "Section not found");
            }
        }
        moderatorScopeRepository.deleteByUserId(targetUserId);
        for (Long sectionId : sectionIds) {
            moderatorScopeRepository.save(new CommunityModeratorScope(targetUserId, sectionId));
        }
        auditInternal(actor, "MODERATOR_SECTIONS_CHANGED", "USER", targetUserId, normalizedReason);
        return toUserView(target);
    }

    @Transactional
    public UserView setPostingRestriction(Long actor,
                                          Long targetUserId,
                                          Boolean restricted,
                                          Instant until,
                                          String reason) {
        lockAndRequireManager(actor);
        if (restricted == null) {
            throw failure(HttpStatus.BAD_REQUEST, "restricted is required");
        }
        if (until != null && until.isBefore(Instant.now())) {
            throw failure(HttpStatus.BAD_REQUEST, "until must be in the future");
        }
        String normalizedReason = normalizeReason(reason);
        User target = lockUser(targetUserId);
        if (restricted) {
            restrictionRepository.save(new CommunityPostingRestriction(targetUserId, until));
        } else {
            restrictionRepository.deleteById(targetUserId);
        }
        auditInternal(actor, "POSTING_RESTRICTION_CHANGED", "USER", targetUserId, normalizedReason);
        return toUserView(target);
    }

    @Transactional(readOnly = true)
    public List<SectionView> listSections(Long actor) {
        requireAdmin(actor);
        return sectionRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(this::toSectionView).toList();
    }

    @Transactional
    public SectionView createSection(Long actor, SectionRequest request) {
        lockAndRequireManager(actor);
        SectionValues values = normalizeSectionRequest(request, false, null);
        ensureSlugAvailable(values.slug(), null);
        Section section = new Section();
        applySectionValues(section, values);
        Section saved = sectionRepository.save(section);
        sectionRepository.flush();
        auditInternal(actor, "SECTION_CREATED", "SECTION", saved.getId(), values.reason());
        evictSectionsCacheAfterCommit();
        return toSectionView(saved);
    }

    @Transactional
    public SectionView updateSection(Long actor, Long sectionId, SectionRequest request) {
        lockAndRequireManager(actor);
        SectionValues values = normalizeSectionRequest(request, true, sectionId);
        Section section = sectionGovernanceRepository.findByIdForUpdate(sectionId).orElse(null);
        if (section == null) {
            throw failure(HttpStatus.NOT_FOUND, "Section not found");
        }
        ensureSlugAvailable(values.slug(), sectionId);
        applySectionValues(section, values);
        Section saved = sectionRepository.save(section);
        sectionRepository.flush();
        auditInternal(actor, "SECTION_UPDATED", "SECTION", saved.getId(), values.reason());
        evictSectionsCacheAfterCommit();
        return toSectionView(saved);
    }

    @Transactional(readOnly = true)
    public Page<AuditView> listAudit(int page, int size, Long actor) {
        requireAdmin(actor);
        return auditRepository.findAllByOrderByOccurredAtDescIdDesc(pageRequest(page, size)).map(this::toAuditView);
    }

    public UserView toUserView(User user) {
        if (user == null || user.getId() == null) {
            throw failure(HttpStatus.NOT_FOUND, "User not found");
        }
        Access access = access(user.getId());
        return new UserView(user.getId(), user.getUsername(), access.admin(), access.moderatedSectionIds(),
                access.postingRestricted(), access.restrictedUntil());
    }

    public SectionView toSectionView(Section section) {
        return new SectionView(section.getId(), section.getName(), section.getSlug(), section.getDescription(),
                section.getRules(), Boolean.TRUE.equals(section.getVisible()), readArchived(section));
    }

    private CommunityState lockPolicyRow() {
        Optional<CommunityState> found = stateRepository.findByIdForUpdate(CommunityState.SINGLETON_ID);
        if (found.isPresent()) {
            return found.get();
        }
        CommunityState created = new CommunityState();
        created.setId(CommunityState.SINGLETON_ID);
        created.setBootstrapped(false);
        CommunityState saved = stateRepository.saveAndFlush(created);
        return saved == null ? created : saved;
    }

    private CommunityAudit auditInternal(Long actor,
                                         String action,
                                         String targetType,
                                         Long targetId,
                                         String reason) {
        String normalizedReason = normalizeReason(reason);
        String normalizedAction = normalizeToken(action, 128, "action");
        String normalizedType = normalizeToken(targetType, 64, "targetType");
        CommunityAudit event = new CommunityAudit();
        event.setActorId(actor);
        event.setAction(normalizedAction);
        event.setTargetType(normalizedType);
        event.setTargetId(targetId);
        event.setReason(normalizedReason);
        event.setOccurredAt(Instant.now());
        String requestId = MDC.get("requestId");
        event.setRequestId(requestId == null || requestId.isBlank() || requestId.length() > 128
                ? UUID.randomUUID().toString() : requestId);
        CommunityAudit saved = auditRepository.save(event);
        return saved == null ? event : saved;
    }

    private User lockUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw failure(HttpStatus.NOT_FOUND, "User not found");
        }
        return userRepository.findByIdForInteraction(userId)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "User not found"));
    }

    private void lockAndRequireManager(Long actor) {
        if (actor == null) {
            throw failure(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        lockPolicy();
        requireAdmin(actor);
    }

    private void requireAdmin(Long actor) {
        if (actor == null) {
            throw failure(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!isAdmin(actor)) {
            throw failure(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 1) {
            throw failure(HttpStatus.BAD_REQUEST, "page must be positive");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw failure(HttpStatus.BAD_REQUEST, "size must be between 1 and 50");
        }
        return PageRequest.of(page - 1, size);
    }

    private String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw failure(HttpStatus.BAD_REQUEST, "q must be at most 128 characters");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private Set<Long> normalizeSectionIds(Collection<Long> sectionIds) {
        if (sectionIds == null) {
            throw failure(HttpStatus.BAD_REQUEST, "sectionIds is required; use an empty array to revoke scopes");
        }
        if (sectionIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : sectionIds) {
            if (id == null || id <= 0) {
                throw failure(HttpStatus.BAD_REQUEST, "sectionIds must contain positive ids");
            }
            normalized.add(id);
        }
        return normalized;
    }

    private SectionValues normalizeSectionRequest(SectionRequest request, boolean update, Long sectionId) {
        if (request == null) {
            throw failure(HttpStatus.BAD_REQUEST, "section request is required");
        }
        String name = normalizeBounded(request.name(), 128, "name");
        String slug = normalizeSlug(request.slug());
        String description = normalizeOptionalBounded(request.description(), MAX_TEXT_LENGTH, "description");
        String rules = normalizeOptionalBounded(request.rules(), MAX_TEXT_LENGTH, "rules");
        boolean visible = request.visible() == null || request.visible();
        Boolean archived = request.archived();
        if (!update && archived == null) {
            archived = false;
        }
        return new SectionValues(name, slug, description, rules, visible, archived,
                normalizeReason(request.reason()));
    }

    private void applySectionValues(Section section, SectionValues values) {
        section.setName(values.name());
        section.setSlug(values.slug());
        section.setDescription(values.description());
        section.setRules(values.rules());
        section.setVisible(values.visible());
        if (values.archived() != null) {
            writeArchived(section, values.archived());
        }
    }

    private void ensureSlugAvailable(String slug, Long sectionId) {
        Optional<Section> duplicate = sectionId == null
                ? sectionGovernanceRepository.findBySlug(slug)
                : sectionGovernanceRepository.findBySlugAndIdNot(slug, sectionId);
        if (duplicate.isPresent()) {
            throw failure(HttpStatus.CONFLICT, "Section slug already exists");
        }
    }

    private String normalizeSlug(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 1 || normalized.length() > 128 || !SECTION_SLUG.matcher(normalized).matches()) {
            throw failure(HttpStatus.BAD_REQUEST, "slug must use lowercase ASCII letters, digits and hyphens");
        }
        return normalized;
    }

    private String normalizeBounded(String value, int max, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw failure(HttpStatus.BAD_REQUEST, field + " must be between 1 and " + max + " characters");
        }
        return normalized;
    }

    private String normalizeOptionalBounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw failure(HttpStatus.BAD_REQUEST, field + " must be at most " + max + " characters");
        }
        return normalized;
    }

    private String normalizeToken(String value, int max, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw failure(HttpStatus.BAD_REQUEST, field + " is invalid");
        }
        return normalized;
    }

    private boolean readArchived(Section section) {
        return Boolean.TRUE.equals(section.getArchived());
    }

    private void writeArchived(Section section, boolean archived) {
        section.setArchived(archived);
    }

    private void evictSectionsCacheAfterCommit() {
        if (redisTemplate == null) {
            return;
        }
        Runnable evict = () -> {
            try {
                RedisCacheEvictor.evictMatching(redisTemplate, "cache:sections:list:*");
            } catch (RuntimeException ignored) {
                // Cache is an optimization; a Redis outage must not roll back DB governance.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }

    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    public record Access(boolean admin,
                         List<Long> moderatedSectionIds,
                         boolean postingRestricted,
                         Instant restrictedUntil) {
        public Access {
            moderatedSectionIds = moderatedSectionIds == null ? List.of() : List.copyOf(moderatedSectionIds);
        }
    }

    private record SectionValues(String name,
                                 String slug,
                                 String description,
                                 String rules,
                                 boolean visible,
                                 Boolean archived,
                                 String reason) {
    }

    private AuditView toAuditView(CommunityAudit audit) {
        return new AuditView(audit.getId(), audit.getActorId(), audit.getAction(), audit.getTargetType(),
                audit.getTargetId(), audit.getReason(), audit.getOccurredAt(), audit.getRequestId());
    }
}
