package com.example.demo.user.service;

import com.example.demo.Demo1Application;
import com.example.demo.user.dto.BasicInfoUpdateRequest;
import com.example.demo.user.dto.ChangePasswordRequest;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for account-row updates that must not overwrite each
 * other's columns. The first test also records the bounded lock observation;
 * the timeout is never treated as a successful request.
 */
@SpringBootTest(
        classes = Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "app.rabbit.enabled=false",
                "feature.mp.read.threads=false"
        })
@ActiveProfiles("test")
@DisabledInAotMode
@Import(UserUpdateConcurrencyIntegrationTest.TestPasswordEncoderConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserUpdateConcurrencyIntegrationTest {

    private static final String DATABASE_NAME = "user_update_race_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final String CURRENT_PASSWORD = "CurrentPassword1";
    private static final String NEW_PASSWORD = "NewPassword2";
    private static final String OTHER_NEW_PASSWORD = "OtherPassword3";

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ControlledPasswordEncoder passwordEncoder;

    private Long userId;
    private String initialHash;
    private String updatedUsername;
    private String updatedEmail;
    private String updatedPhone;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false;LOCK_TIMEOUT=10000");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void setUp() {
        passwordEncoder.reset();
        userRepository.deleteAllInBatch();

        String suffix = DATABASE_NAME.substring(DATABASE_NAME.length() - 12);
        String initialUsername = "race_old_" + suffix;
        String initialEmail = "race_old_" + suffix + "@example.test";
        updatedUsername = "race_new_" + suffix;
        updatedEmail = "race_new_" + suffix + "@example.test";
        updatedPhone = "13900000002";

        User user = new User();
        user.setUsername(initialUsername);
        user.setEmail(initialEmail);
        user.setPhone("13900000001");
        user.setPasswordHash(passwordEncoder.encode(CURRENT_PASSWORD));
        user.setCreatedAt(OffsetDateTime.now());
        User saved = userRepository.saveAndFlush(user);

        userId = saved.getId();
        initialHash = saved.getPasswordHash();
    }

    @Test
    void password_snapshot_waits_for_account_lock_and_both_updates_survive() throws Exception {
        passwordEncoder.armPauseBeforePasswordMatch();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> passwordUpdate = null;
        Future<UserDto> profileUpdate = null;
        try {
            passwordUpdate = workers.submit(() -> inNewTransaction(() -> {
                ChangePasswordRequest request = new ChangePasswordRequest();
                request.setCurrentPassword(CURRENT_PASSWORD);
                request.setNewPassword(NEW_PASSWORD);
                userService.changePassword(userId, request);
                return null;
            }));

            assertThat(passwordEncoder.awaitPasswordMatchEntry())
                    .as("T1 must reach password matching after locking the old row")
                    .isTrue();
            assertThat(passwordEncoder.lastMatchedHash())
                    .as("T1 must have matched the initial database hash")
                    .isEqualTo(initialHash);

            CountDownLatch t2Started = new CountDownLatch(1);
            profileUpdate = workers.submit(() -> {
                t2Started.countDown();
                return inNewTransaction(() -> {
                    BasicInfoUpdateRequest request = new BasicInfoUpdateRequest();
                    request.setUsername(updatedUsername);
                    request.setEmail(updatedEmail);
                    request.setPhone(updatedPhone);
                    return userService.updateBasicInfo(userId, request);
                });
            });
            assertThat(t2Started.await(10, TimeUnit.SECONDS))
                    .as("T2 worker must start before the bounded lock observation")
                    .isTrue();

            boolean t2CompletedBeforeRelease = false;
            try {
                profileUpdate.get(500, TimeUnit.MILLISECONDS);
                t2CompletedBeforeRelease = true;
            } catch (TimeoutException expectedLockWait) {
                // A locked T2 is expected here; release T1 before judging either result.
            }
            System.out.printf(
                    "RACE_LOCK_OBSERVATION t1=paused_after_old_snapshot "
                            + "t2CompletedBeforeRelease=%s%n",
                    t2CompletedBeforeRelease);

            // Always release T1. A bounded timeout above is only an observation,
            // never evidence that T2 succeeded.
            passwordEncoder.releasePasswordMatch();
            passwordUpdate.get(30, TimeUnit.SECONDS);
            UserDto profileResponse = profileUpdate.get(30, TimeUnit.SECONDS);
            System.out.println("RACE_REQUEST_RESULTS t1=password_success t2=profile_success");

            User stored = inNewTransaction(() -> userRepository.findById(userId).orElseThrow());
            boolean newPasswordMatches = passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash());
            boolean currentPasswordMatches = passwordEncoder.matches(CURRENT_PASSWORD, stored.getPasswordHash());
            boolean t2WaitedForLock = !t2CompletedBeforeRelease;
            System.out.printf(
                    "RACE_FINAL_READBACK userId=%d username=%s email=%s phone=%s "
                            + "newPasswordMatches=%s currentPasswordMatches=%s%n",
                    stored.getId(), stored.getUsername(), stored.getEmail(), stored.getPhone(),
                    newPasswordMatches, currentPasswordMatches);

            SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(t2WaitedForLock)
                        .as("T2 should still be waiting for T1's account-row lock")
                        .isTrue();
                softly.assertThat(profileResponse.getUsername()).isEqualTo(updatedUsername);
                softly.assertThat(profileResponse.getEmail()).isEqualTo(updatedEmail);
                softly.assertThat(profileResponse.getPhone()).isEqualTo(updatedPhone);
                softly.assertThat(stored.getUsername())
                        .as("final H2 username")
                        .isEqualTo(updatedUsername);
                softly.assertThat(stored.getEmail())
                        .as("final H2 email")
                        .isEqualTo(updatedEmail);
                softly.assertThat(stored.getPhone())
                        .as("final H2 phone")
                        .isEqualTo(updatedPhone);
                softly.assertThat(newPasswordMatches)
                        .as("final H2 password must match the new password")
                        .isTrue();
                softly.assertThat(currentPasswordMatches)
                        .as("final H2 password must reject the old password")
                        .isFalse();
            });
        } finally {
            passwordEncoder.releasePasswordMatch();
            workers.shutdownNow();
            workers.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrent_password_changes_with_same_old_password_have_one_winner() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<PasswordAttempt> first = workers.submit(
                    () -> runPasswordChange(start, NEW_PASSWORD));
            Future<PasswordAttempt> second = workers.submit(
                    () -> runPasswordChange(start, OTHER_NEW_PASSWORD));

            List<PasswordAttempt> attempts = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            User stored = inNewTransaction(() -> userRepository.findById(userId).orElseThrow());
            boolean firstPasswordMatches = passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash());
            boolean secondPasswordMatches = passwordEncoder.matches(OTHER_NEW_PASSWORD, stored.getPasswordHash());
            boolean currentPasswordMatches = passwordEncoder.matches(CURRENT_PASSWORD, stored.getPasswordHash());
            long winners = attempts.stream().filter(PasswordAttempt::succeeded).count();
            PasswordAttempt loser = attempts.stream()
                    .filter(attempt -> !attempt.succeeded())
                    .findFirst()
                    .orElse(null);
            System.out.printf(
                    "PASSWORD_RACE_RESULTS winners=%d firstStatus=%s secondStatus=%s "
                            + "firstPasswordMatches=%s secondPasswordMatches=%s "
                            + "currentPasswordMatches=%s%n",
                    winners, attempts.get(0).statusCode(), attempts.get(1).statusCode(),
                    firstPasswordMatches, secondPasswordMatches, currentPasswordMatches);

            assertThat(winners)
                    .as("exactly one same-old-password request may commit")
                    .isEqualTo(1);
            assertThat(loser).isNotNull();
            assertThat(loser.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(loser.reason()).isEqualTo("当前密码不正确");
            assertThat(firstPasswordMatches ^ secondPasswordMatches)
                    .as("the final password must be exactly one winner")
                    .isTrue();
            assertThat(currentPasswordMatches).isFalse();
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private PasswordAttempt runPasswordChange(CyclicBarrier start, String newPassword) throws Exception {
        start.await(30, TimeUnit.SECONDS);
        try {
            inNewTransaction(() -> {
                ChangePasswordRequest request = new ChangePasswordRequest();
                request.setCurrentPassword(CURRENT_PASSWORD);
                request.setNewPassword(newPassword);
                userService.changePassword(userId, request);
                return null;
            });
            return PasswordAttempt.success();
        } catch (ResponseStatusException exception) {
            return PasswordAttempt.failure(exception.getStatusCode().value(), exception.getReason());
        }
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private record PasswordAttempt(boolean succeeded, Integer statusCode, String reason) {
        private static PasswordAttempt success() {
            return new PasswordAttempt(true, null, null);
        }

        private static PasswordAttempt failure(int statusCode, String reason) {
            return new PasswordAttempt(false, statusCode, reason);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPasswordEncoderConfiguration {

        @Bean
        @Primary
        ControlledPasswordEncoder controlledPasswordEncoder() {
            return new ControlledPasswordEncoder();
        }
    }

    static final class ControlledPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate = new BCryptPasswordEncoder();
        private final AtomicBoolean pauseNextMatch = new AtomicBoolean();
        private volatile CountDownLatch matchEntered = new CountDownLatch(0);
        private volatile CountDownLatch releaseMatch = new CountDownLatch(0);
        private volatile String lastMatchedHash;

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            if (pauseNextMatch.compareAndSet(true, false)) {
                lastMatchedHash = encodedPassword;
                matchEntered.countDown();
                await(releaseMatch, "T1 password match release");
            }
            return delegate.matches(rawPassword, encodedPassword);
        }

        void reset() {
            pauseNextMatch.set(false);
            matchEntered = new CountDownLatch(0);
            releaseMatch = new CountDownLatch(0);
            lastMatchedHash = null;
        }

        void armPauseBeforePasswordMatch() {
            matchEntered = new CountDownLatch(1);
            releaseMatch = new CountDownLatch(1);
            lastMatchedHash = null;
            pauseNextMatch.set(true);
        }

        boolean awaitPasswordMatchEntry() {
            return await(matchEntered, "T1 password match entry");
        }

        String lastMatchedHash() {
            return lastMatchedHash;
        }

        void releasePasswordMatch() {
            pauseNextMatch.set(false);
            releaseMatch.countDown();
        }

        private static boolean await(CountDownLatch latch, String name) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(name + " latch timed out");
                }
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(name + " latch interrupted", exception);
            }
        }
    }
}
