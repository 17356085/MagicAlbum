package com.example.demo.bootstrap.local;

import com.example.demo.bootstrap.local.seed.LocalSectionSeeds;
import com.example.demo.bootstrap.local.seed.LocalThreadSeedText;
import com.example.demo.bootstrap.local.seed.LocalUserSeeds;
import com.example.demo.bootstrap.local.seed.SectionSeed;
import com.example.demo.bootstrap.local.seed.UserSeed;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("local")
public class LocalSampleDataSeeder implements ApplicationRunner {
    private static final String DEFAULT_PASSWORD = "BlueAlbum123";
    private static final Logger log = LoggerFactory.getLogger(LocalSampleDataSeeder.class);

    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ThreadRepository threadRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalSampleDataSeeder(
            SectionRepository sectionRepository,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ThreadRepository threadRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.sectionRepository = sectionRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.threadRepository = threadRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, Section> sectionsBySlug = ensureSections();
        List<UserSeed> userSeeds = LocalUserSeeds.build();
        Map<String, User> usersByUsername = ensureUsers(userSeeds);
        ensureThreads(sectionsBySlug, usersByUsername, userSeeds);
        log.info(
                "Local sample data ready: sections={}, users={}, threads={}, samplePassword={}",
                sectionRepository.count(),
                userRepository.count(),
                threadRepository.count(),
                DEFAULT_PASSWORD
        );
    }

    private Map<String, Section> ensureSections() {
        Map<String, Section> existingBySlug = new HashMap<>();
        for (Section section : sectionRepository.findAll()) {
            existingBySlug.put(section.getSlug(), section);
        }

        for (SectionSeed seed : LocalSectionSeeds.build()) {
            if (existingBySlug.containsKey(seed.slug())) {
                continue;
            }
            Section section = new Section();
            section.setName(seed.name());
            section.setSlug(seed.slug());
            section.setDescription(seed.description());
            section.setRules(seed.rules());
            section.setVisible(true);
            existingBySlug.put(seed.slug(), sectionRepository.save(section));
        }
        return existingBySlug;
    }

    private Map<String, User> ensureUsers(List<UserSeed> userSeeds) {
        Map<String, User> existingByUsername = new HashMap<>();
        for (User user : userRepository.findAll()) {
            existingByUsername.put(user.getUsername(), user);
        }

        for (UserSeed seed : userSeeds) {
            User user = existingByUsername.get(seed.username());
            if (user == null) {
                user = new User();
                user.setUsername(seed.username());
                user.setEmail(seed.email());
                user.setPhone(seed.phone());
                user.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
                user.setCreatedAt(OffsetDateTime.now().minusDays(seed.index() % 18L).minusHours(seed.index()));
                user = userRepository.save(user);
                existingByUsername.put(seed.username(), user);
            }
            ensureProfile(user, seed);
        }
        return existingByUsername;
    }

    private void ensureProfile(User user, UserSeed seed) {
        UserProfile existing = userProfileRepository.findByUserId(user.getId());
        if (existing != null) {
            return;
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(user.getId());
        profile.setNickname(seed.nickname());
        profile.setBio(seed.bio());
        profile.setLocation(seed.location());
        profile.setHomepageUrl(seed.homepage());
        profile.setLinks(List.of(seed.link()));
        userProfileRepository.save(profile);
    }

    private void ensureThreads(Map<String, Section> sectionsBySlug, Map<String, User> usersByUsername, List<UserSeed> userSeeds) {
        if (threadRepository.count() > 0) {
            return;
        }

        List<Thread> threads = new ArrayList<>();
        List<SectionSeed> sectionSeeds = LocalSectionSeeds.build();
        List<String> sectionSlugs = sectionSeeds.stream().map(SectionSeed::slug).toList();
        List<String> intros = LocalThreadSeedText.intros();
        List<String> endings = LocalThreadSeedText.endings();

        for (UserSeed seed : userSeeds) {
            User user = usersByUsername.get(seed.username());
            for (int i = 0; i < 2; i++) {
                String slug = sectionSlugs.get((seed.index() * 2 + i) % sectionSlugs.size());
                Section section = sectionsBySlug.get(slug);
                Thread thread = new Thread();
                thread.setSectionId(section.getId());
                thread.setAuthorId(user.getId());
                thread.setTitle(LocalThreadSeedText.threadTitle(section.getName(), seed.nickname(), i));
                thread.setContentMd(LocalThreadSeedText.threadContent(section, seed, intros.get((seed.index() + i) % intros.size()), endings.get((seed.index() + i) % endings.size())));
                thread.setStatus("NORMAL");
                thread.setSpoiler(false);
                Instant createdAt = Instant.now().minusSeconds((long) (seed.index() * 2 + i + 1) * 7200L);
                thread.setCreatedAt(createdAt);
                thread.setUpdatedAt(createdAt.plusSeconds(900));
                thread.setSummaryStatus("PENDING");
                threads.add(thread);
            }
        }
        threadRepository.saveAll(threads);
    }
}
