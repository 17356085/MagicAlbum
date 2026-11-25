package com.example.demo.sections.service;

import com.example.demo.common.RedisKeys;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Content-owned administrative operations for sections.
 *
 * <p>The governance module supplies policy decisions and receives value
 * projections. Section entities and repository operations remain inside the
 * content module, including the row locks and the list-cache generation
 * boundary.</p>
 */
@Service
public class SectionAdministration {
    private final SectionRepository sectionRepository;
    private final StringRedisTemplate redisTemplate;

    public SectionAdministration(SectionRepository sectionRepository) {
        this(sectionRepository, (StringRedisTemplate) null);
    }

    @Autowired
    public SectionAdministration(SectionRepository sectionRepository,
                                 ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(sectionRepository,
                redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable());
    }

    SectionAdministration(SectionRepository sectionRepository,
                          StringRedisTemplate redisTemplate) {
        this.sectionRepository = Objects.requireNonNull(sectionRepository, "sectionRepository");
        this.redisTemplate = redisTemplate;
    }

    /** Locks the section with the shared posting lock and returns a value view. */
    @Transactional
    public Optional<SectionView> findByIdForWriting(Long sectionId) {
        return sectionRepository.findByIdForWriting(sectionId).map(this::toView);
    }

    public boolean existsById(Long sectionId) {
        return sectionRepository.existsById(sectionId);
    }

    /** Returns the IDs that actually exist, preserving the caller's set semantics. */
    @Transactional(readOnly = true)
    public Set<Long> findExistingIds(Collection<Long> sectionIds) {
        if (sectionIds == null || sectionIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> found = new LinkedHashSet<>();
        for (Section section : sectionRepository.findAllById(sectionIds)) {
            if (section != null && section.getId() != null) {
                found.add(section.getId());
            }
        }
        return Set.copyOf(found);
    }

    /** Lists sections for governance without leaking the content entity. */
    @Transactional(readOnly = true)
    public List<SectionView> listAll() {
        return sectionRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(this::toView).toList();
    }

    /** Creates and flushes a section in the caller's ambient transaction. */
    @Transactional
    public SectionView create(SectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (sectionRepository.findBySlug(settings.slug()).isPresent()) {
            throw new SlugAlreadyExistsException();
        }
        Section section = new Section();
        applySettings(section, settings);
        Section saved = sectionRepository.save(section);
        sectionRepository.flush();
        invalidateSectionsCacheAfterCommit();
        return toView(saved);
    }

    /**
     * Locks, validates the natural key, updates and flushes one section in the
     * caller's ambient transaction. A missing section is reported as empty so
     * the governance layer can retain its existing HTTP error semantics.
     */
    @Transactional
    public Optional<SectionView> update(Long sectionId, SectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        Section section = sectionRepository.findByIdForUpdate(sectionId).orElse(null);
        if (section == null) {
            return Optional.empty();
        }
        if (sectionRepository.findBySlugAndIdNot(settings.slug(), sectionId).isPresent()) {
            throw new SlugAlreadyExistsException();
        }
        applySettings(section, settings);
        Section saved = sectionRepository.save(section);
        sectionRepository.flush();
        invalidateSectionsCacheAfterCommit();
        return Optional.of(toView(saved));
    }

    private void applySettings(Section section, SectionSettings settings) {
        section.setName(settings.name());
        section.setSlug(settings.slug());
        section.setDescription(settings.description());
        section.setRules(settings.rules());
        section.setVisible(settings.visible());
        if (settings.archived() != null) {
            section.setArchived(settings.archived());
        }
    }

    private SectionView toView(Section section) {
        return new SectionView(section.getId(), section.getName(), section.getSlug(),
                section.getDescription(), section.getRules(), Boolean.TRUE.equals(section.getVisible()),
                Boolean.TRUE.equals(section.getArchived()));
    }

    private void invalidateSectionsCacheAfterCommit() {
        if (redisTemplate == null) {
            return;
        }
        Runnable invalidate = () -> {
            try {
                redisTemplate.opsForValue().set(
                        RedisKeys.sectionsListGeneration(), UUID.randomUUID().toString());
            } catch (RuntimeException ignored) {
                // Cache is an optimization; a Redis outage must not roll back DB governance.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }

    public record SectionSettings(String name,
                                  String slug,
                                  String description,
                                  String rules,
                                  boolean visible,
                                  Boolean archived) {
    }

    public record SectionView(Long id,
                              String name,
                              String slug,
                              String description,
                              String rules,
                              boolean visible,
                              boolean archived) {
    }

    /** Internal content result translated to the governance API's 409 response. */
    public static final class SlugAlreadyExistsException extends RuntimeException {
        public SlugAlreadyExistsException() {
            super("Section slug already exists");
        }
    }
}
