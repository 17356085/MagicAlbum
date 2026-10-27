package com.example.demo.user.service;

import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_list_queries;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
class UserServiceListIntegrationTest {
    @Autowired UserService userService;
    @Autowired EntityManager entityManager;

    private Statistics statistics;

    @BeforeEach
    void createUsers() {
        for (int i = 0; i < 21; i++) {
            User user = new User();
            user.setUsername("list-user-" + i);
            user.setEmail("list-user-" + i + "@example.com");
            user.setPhone(String.format("139%08d", i));
            user.setPasswordHash("test-only-hash");
            entityManager.persist(user);
            if (i % 2 == 0) {
                UserProfile profile = new UserProfile();
                profile.setUserId(user.getId());
                profile.setNickname("列表昵称-" + i);
                profile.setAvatarUrl("/uploads/avatar-" + i + ".png");
                entityManager.persist(profile);
            }
        }
        entityManager.flush();
        entityManager.clear();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void profileQueriesStayConstantAsPageSizeGrows() {
        for (int size : new int[]{1, 20}) {
            entityManager.clear();
            statistics.clear();

            var page = userService.list(null, 1, size, null);

            assertThat(page.getTotalElements()).isEqualTo(21);
            assertThat(page.getContent()).hasSize(size);
            // A full page executes a user query, a count query and one profile query.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
            assertThat(page.getContent()).allSatisfy(user -> {
                int index = Integer.parseInt(user.getUsername().substring("list-user-".length()));
                assertThat(user.getNickname()).isEqualTo(index % 2 == 0 ? "列表昵称-" + index : null);
                assertThat(user.getAvatarUrl()).isEqualTo(index % 2 == 0 ? "/uploads/avatar-" + index + ".png" : null);
            });
        }
    }

    @Test
    void nicknameSearchReturnsMatchingProfilesWithBoundedQueries() {
        var page = userService.list("列表昵称", 1, 5, "username,nickname");

        assertThat(page.getTotalElements()).isEqualTo(11);
        assertThat(page.getContent()).hasSize(5).allSatisfy(user -> {
            assertThat(user.getNickname()).startsWith("列表昵称-");
            assertThat(user.getAvatarUrl()).startsWith("/uploads/avatar-");
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void publicSearchDoesNotMatchEmailOrPhoneOutsideSelectedFields() {
        for (String keyword : new String[]{"example.com", "139"}) {
            statistics.clear();

            var page = userService.list(keyword, 1, 5, "username,nickname");

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        }
    }

    @Test
    void nicknameOnlySearchDoesNotImplicitlySearchUsername() {
        var page = userService.list("list-user", 1, 5, " nickname ");

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void defaultPublicSearchDoesNotMatchPrivateContactFields() {
        for (String keyword : new String[]{"example.com", "139"}) {
            var page = userService.list(keyword, 1, 5, null);

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
        }
    }

    @Test
    void selectedFieldsAreCaseInsensitiveAndKeepTotalAcrossPages() {
        for (int pageNumber : new int[]{1, 2, 3}) {
            var page = userService.list("列表昵称", pageNumber, 5, " USERNAME , NickName ");

            assertThat(page.getTotalElements()).isEqualTo(11);
            assertThat(page.getContent()).hasSize(pageNumber == 3 ? 1 : 5)
                    .allSatisfy(user -> assertThat(user.getNickname()).startsWith("列表昵称-"));
        }
    }

    @Test
    void usernameOnlySearchDoesNotMatchNicknameAndDefaultIncludesNickname() {
        var usernameOnly = userService.list("列表昵称", 1, 5, "username");
        assertThat(usernameOnly.getContent()).isEmpty();
        assertThat(usernameOnly.getTotalElements()).isZero();

        var defaultFields = userService.list("列表昵称", 1, 5, null);
        assertThat(defaultFields.getTotalElements()).isEqualTo(11);
        assertThat(defaultFields.getContent()).hasSize(5);
    }
}
