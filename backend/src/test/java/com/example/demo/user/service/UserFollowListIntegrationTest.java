package com.example.demo.user.service;

import com.example.demo.user.dto.ProfileDto;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserFollow;
import com.example.demo.user.entity.UserProfile;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_follow_list_queries;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
class UserFollowListIntegrationTest {
    @Autowired UserFollowService userFollowService;
    @Autowired UserProfileService userProfileService;
    @Autowired EntityManager entityManager;

    private Long ownerId;
    private final List<Long> followerIds = new ArrayList<>();
    private final List<Long> followingIds = new ArrayList<>();
    private final Map<Long, String> usernamesById = new LinkedHashMap<>();
    private final Map<Long, OffsetDateTime> createdAtById = new LinkedHashMap<>();
    private final Map<Long, String> nicknamesById = new LinkedHashMap<>();
    private final Map<Long, String> avatarsById = new LinkedHashMap<>();
    private int nextPhoneSuffix;
    private Statistics statistics;

    @BeforeEach
    void createFixture() {
        followerIds.clear();
        followingIds.clear();
        usernamesById.clear();
        createdAtById.clear();
        nicknamesById.clear();
        avatarsById.clear();
        nextPhoneSuffix = 0;

        User owner = persistUser("follow-list-owner", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        ownerId = owner.getId();

        for (int i = 0; i < 21; i++) {
            User follower = persistUser("follower-" + i, userCreatedAt(2, i));
            followerIds.add(follower.getId());
            addSummaryMetadata(follower, i, "follower-nickname-" + i, "/avatars/follower-" + i + ".png");

            User following = persistUser("following-" + i, userCreatedAt(3, i));
            followingIds.add(following.getId());
            addSummaryMetadata(following, i, "following-nickname-" + i, "/avatars/following-" + i + ".png");
        }
        entityManager.flush();

        for (int i = 0; i < 21; i++) {
            persistFollow(followerIds.get(i), ownerId, relationCreatedAt(1, i));
            persistFollow(ownerId, followingIds.get(i), relationCreatedAt(4, i));
        }

        // Index 20 has a populated profile, index 19 has null profile values,
        // and index 18 has no profile. The remaining profiles keep the fixture realistic.
        for (int i = 0; i < 21; i++) {
            addProfile(followerIds.get(i), i, "follower-nickname-" + i,
                    "/avatars/follower-" + i + ".png");
            addProfile(followingIds.get(i), i, "following-nickname-" + i,
                    "/avatars/following-" + i + ".png");
        }
        entityManager.flush();
        entityManager.clear();

        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void listFollowersAndFollowingUseFiveStatementsForPageSizesOneAndTwenty() {
        Map<String, Long> observed = new LinkedHashMap<>();
        Map<String, Long> expected = new LinkedHashMap<>();

        for (String direction : List.of("followers", "following")) {
            for (int size : new int[]{1, 20}) {
                entityManager.clear();
                statistics.clear();
                Page<UserSummaryDto> page = "followers".equals(direction)
                        ? userFollowService.listFollowers(ownerId, 1, size)
                        : userFollowService.listFollowing(ownerId, 1, size);
                long statementCount = statistics.getPrepareStatementCount();
                String key = direction + " page=1 size=" + size;
                System.out.println("SQL_STATEMENTS " + key + " actual=" + statementCount);
                observed.put(key, statementCount);
                expected.put(key, 5L);
                assertThat(page.getContent()).hasSize(size);
            }
        }

        assertThat(observed).containsExactlyEntriesOf(expected);
    }

    @Test
    void summariesPreserveOrderingPaginationCreatedAtAndProfileFallbacks() {
        Page<UserSummaryDto> followersPage = userFollowService.listFollowers(ownerId, 1, 3);
        assertSummaryPage(followersPage, reverse(followerIds), 0, 3, true);

        Page<UserSummaryDto> followingPage = userFollowService.listFollowing(ownerId, 1, 3);
        assertSummaryPage(followingPage, reverse(followingIds), 0, 3, true);

        Page<UserSummaryDto> followersPageTwo = userFollowService.listFollowers(ownerId, 2, 20);
        assertSummaryPage(followersPageTwo, reverse(followerIds), 1, 20, false);
        assertThat(followersPageTwo.getTotalPages()).isEqualTo(2);
        assertThat(followersPageTwo.hasPrevious()).isTrue();
    }

    @Test
    void existingProfileBatchStillIncludesFollowCounts() {
        entityManager.clear();
        Map<Long, ProfileDto> profiles = userProfileService.getProfiles(
                List.of(followerIds.get(20), followingIds.get(20)));

        assertThat(profiles.get(followerIds.get(20)).getFollowerCount()).isZero();
        assertThat(profiles.get(followerIds.get(20)).getFollowingCount()).isEqualTo(1);
        assertThat(profiles.get(followingIds.get(20)).getFollowerCount()).isEqualTo(1);
        assertThat(profiles.get(followingIds.get(20)).getFollowingCount()).isZero();
    }

    @Test
    void missingUsersAreSkippedWhileFollowPageMetadataIsRetained() {
        entityManager.createQuery("delete from User u where u.id = :id")
                .setParameter("id", followerIds.get(20))
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        Page<UserSummaryDto> page = userFollowService.listFollowers(ownerId, 1, 20);

        assertThat(page.getContent()).hasSize(19);
        assertThat(page.getContent()).extracting(UserSummaryDto::getId)
                .containsExactlyElementsOf(reverse(followerIds.subList(1, 20)));
        assertThat(page.getTotalElements()).isEqualTo(21);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(20);
        System.out.println("SQL_STATEMENTS missing-user page=1 size=20 actual="
                + statistics.getPrepareStatementCount());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
    }

    @Test
    void emptyFollowPageDoesNotReadUsersOrProfiles() {
        entityManager.clear();
        statistics.clear();

        Page<UserSummaryDto> page = userFollowService.listFollowing(ownerId, 3, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getTotalElements()).isEqualTo(21);
        System.out.println("SQL_STATEMENTS empty page=3 size=20 actual="
                + statistics.getPrepareStatementCount());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    private void assertSummaryPage(Page<UserSummaryDto> page, List<Long> allIdsInOrder,
                                   int expectedPageNumber, int expectedSize, boolean hasNext) {
        List<Long> expectedIds = allIdsInOrder.subList(expectedPageNumber * expectedSize,
                Math.min(allIdsInOrder.size(), (expectedPageNumber + 1) * expectedSize));
        assertThat(page.getContent()).hasSize(expectedIds.size());
        assertThat(page.getContent()).extracting(UserSummaryDto::getId)
                .containsExactlyElementsOf(expectedIds);
        assertThat(page.getNumber()).isEqualTo(expectedPageNumber);
        assertThat(page.getSize()).isEqualTo(expectedSize);
        assertThat(page.getTotalElements()).isEqualTo(21);
        assertThat(page.hasNext()).isEqualTo(hasNext);

        for (UserSummaryDto summary : page.getContent()) {
            Long userId = summary.getId();
            assertThat(summary.getUsername()).isEqualTo(usernamesById.get(userId));
            assertThat(summary.getCreatedAt()).isEqualTo(createdAtById.get(userId));
            assertThat(summary.getNickname()).isEqualTo(nicknamesById.get(userId));
            assertThat(summary.getAvatarUrl()).isEqualTo(avatarsById.get(userId));
        }
    }

    private User persistUser(String username, OffsetDateTime createdAt) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPhone("139" + String.format("%08d", nextPhoneSuffix++));
        user.setPasswordHash("test-only-hash");
        user.setCreatedAt(createdAt);
        entityManager.persist(user);
        return user;
    }

    private void addSummaryMetadata(User user, int index, String nickname, String avatar) {
        usernamesById.put(user.getId(), user.getUsername());
        createdAtById.put(user.getId(), user.getCreatedAt());
        nicknamesById.put(user.getId(), index == 18 ? user.getUsername() : profileValue(index, nickname));
        avatarsById.put(user.getId(), index == 18 || index == 19 ? "" : avatar);
    }

    private void addProfile(Long userId, int index, String nickname, String avatar) {
        if (index == 18) {
            return;
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setNickname(index == 19 ? null : nickname);
        profile.setAvatarUrl(index == 19 ? null : avatar);
        entityManager.persist(profile);
    }

    private void persistFollow(Long followerId, Long followingId, Instant createdAt) {
        UserFollow follow = new UserFollow();
        follow.setFollowerId(followerId);
        follow.setFollowingId(followingId);
        setCreatedAt(follow, createdAt);
        entityManager.persist(follow);
    }

    private static void setCreatedAt(UserFollow follow, Instant createdAt) {
        try {
            Field field = UserFollow.class.getDeclaredField("createdAt");
            field.trySetAccessible();
            field.set(follow, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to set stable follow timestamp", e);
        }
    }

    private static OffsetDateTime userCreatedAt(int day, int index) {
        return OffsetDateTime.of(2026, 1, day, 0, 0, index, 0, ZoneOffset.UTC);
    }

    private static Instant relationCreatedAt(int day, int index) {
        return Instant.parse(String.format("2026-01-%02dT00:00:%02dZ", day, index));
    }

    private static String profileValue(int index, String value) {
        if (index == 19) {
            return "";
        }
        if (index == 18) {
            return null;
        }
        return value;
    }

    private static List<Long> reverse(List<Long> ids) {
        List<Long> reversed = new ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        return reversed;
    }
}
