package com.example.demo.chat;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.chat.dto.ChatModels.Group;
import com.example.demo.chat.dto.ChatModels.Message;
import com.example.demo.chat.dto.ChatModels.Person;
import com.example.demo.chat.dto.ChatModels.Send;
import com.example.demo.chat.service.ChatService;
import com.example.demo.chat.service.ChatDirectory;
import com.example.demo.chat.service.ChatDirectory.Decision;
import com.example.demo.chat.service.ChatDirectory.Purpose;
import com.example.demo.governance.service.GovernanceService;
import com.example.demo.moderation.dto.CreateReportRequest;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.model.ReportAction;
import com.example.demo.moderation.model.ReportReason;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;
import com.example.demo.moderation.service.ContentReportService;
import com.example.demo.social.service.ContactPolicy;
import com.example.demo.social.service.ContactPolicy.BlockedContact;
import com.example.demo.social.service.ContactPolicy.FriendStatus;
import com.example.demo.social.service.UserFollowService;
import com.example.demo.user.dto.ProfileDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.service.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.chat.internal-service-token=" + ChatIntegrationTest.CHAT_CONTRACT_TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatIntegrationTest {
    static final String CHAT_CONTRACT_TOKEN = "ChatServiceOnlyTestCredential_0123456789_abcdef";
    private static final String SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String CONTACT_DECISIONS = "/internal/v1/social/contact-decisions";
    private static final String PROFILE_BATCH = "/internal/v1/users/public-profiles:batchGet";
    private static final String MYSQL_URL = System.getProperty("chat.test.jdbc-url", "");
    private static final String MYSQL_PASSWORD = System.getProperty("chat.test.jdbc-password",
            System.getenv().getOrDefault("BLUEALBUM_CHAT_TEST_JDBC_PASSWORD", ""));

    @Autowired ChatService chat;
    @MockitoSpyBean ChatDirectory directory;
    @Autowired ContactPolicy contacts;
    @Autowired ContentReportService reports;
    @Autowired UserFollowService follows;
    @Autowired UserProfileService profiles;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired GovernanceService governance;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        boolean mysql = !MYSQL_URL.isBlank();
        registry.add("spring.datasource.url", () -> mysql ? MYSQL_URL : "jdbc:h2:mem:chat;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        registry.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> mysql ? System.getProperty("chat.test.jdbc-user", "root") : "sa");
        registry.add("spring.datasource.password", () -> mysql ? MYSQL_PASSWORD : "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> mysql ? "validate" : "create-drop");
        registry.add("spring.jpa.database-platform", () -> mysql
                ? "org.hibernate.dialect.MySQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> mysql
                ? "org.hibernate.dialect.MySQLDialect" : "org.hibernate.dialect.H2Dialect");
        registry.add("spring.flyway.enabled", () -> mysql);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/mysql");
        registry.add("spring.jpa.show-sql", () -> false);
        registry.add("app.chat.allow-requests-default", () -> true);
    }

    @Test
    void httpEntryUsesJwtAndKeepsPrivateResponsesOutOfCaches() throws Exception {
        long actor = user(), recipient = user();
        String url = "/api/v1/chat/direct/" + recipient + "/messages";
        String token = "Bearer " + jwt.generateToken(actor, "chat-test");
        String payload = """
                {"clientMessageId":"%s","content":"hello"}
                """.formatted(UUID.randomUUID());
        mvc.perform(get("/api/v1/chat/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/chat/realtime/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/chat/realtime/session").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(actor))
                .andExpect(jsonPath("$.expiresAt").isNumber());
        mvc.perform(get("/api/v1/chat/direct/0").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.message.sender.id").value(actor))
                .andExpect(jsonPath("$.quota.remaining").value(9));
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quota.remaining").value(9));
    }

    @Test
    void authorizedHistoryKeepsItsMessagesWhenDisplayDirectoryIsUnavailable() {
        long actor = user(), peer = user();
        follows.follow(actor, peer);
        follows.follow(peer, actor);
        var sent = chat.sendDirect(actor, peer, new Send(UUID.randomUUID(), "保留聊天历史"));

        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .when(directory).displayNames(anyCollection());

        assertThat(chat.history(peer, sent.conversationId(), null, null).items()).singleElement()
                .satisfies(message -> {
                    assertThat(message.sender()).isEqualTo(new Person(actor, "用户"));
                    assertThat(message.content()).isEqualTo("保留聊天历史");
                });
        assertThatThrownBy(() -> chat.sendDirect(actor, peer, new Send(UUID.randomUUID(), "新消息")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void privateGroupDecisionsShareOneAuthoritySnapshot() throws Exception {
        long actor = user(), friend = user(), blocked = user();
        follows.follow(actor, friend);
        follows.follow(friend, actor);
        contacts.setBlocked(blocked, actor, true);
        UUID operation = UUID.randomUUID();
        String path = CONTACT_DECISIONS + ":batchGet";
        String payload = """
                {"actorId":"%d","peerIds":["%d","%d"],"purpose":"GROUP_INVITE","operationId":"%s"}
                """.formatted(actor, blocked, friend, operation);

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(SERVICE_TOKEN_HEADER, CHAT_CONTRACT_TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.decisions[0].peerId").value(Long.toString(blocked)))
                .andExpect(jsonPath("$.decisions[0].contactAllowed").value(false))
                .andExpect(jsonPath("$.decisions[1].peerId").value(Long.toString(friend)))
                .andExpect(jsonPath("$.decisions[1].mutual").value(true))
                .andExpect(jsonPath("$.decisions[1].operationId").value(operation.toString())).andReturn();
        var decisions = new ObjectMapper().readTree(response.getResponse().getContentAsString()).get("decisions");
        assertThat(decisions.get(0).get("evaluatedAt")).isEqualTo(decisions.get(1).get("evaluatedAt"));
        assertThat(decisions.get(0).get("expiresAt")).isEqualTo(decisions.get(1).get("expiresAt"));
    }

    @Test
    void privateContactDecisionIsScopedToTheOperationAndCurrentRelationship() throws Exception {
        long actor = user(), peer = user();
        follows.follow(actor, peer);
        follows.follow(peer, actor);
        UUID operation = UUID.randomUUID();
        String payload = """
                {"actorId":"%d","peerId":"%d","purpose":"DIRECT_SEND","operationId":"%s"}
                """.formatted(actor, peer, operation);
        String bearer = "Bearer " + jwt.generateToken(actor, "chat-test");
        mvc.perform(post(CONTACT_DECISIONS).contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(CONTACT_DECISIONS).contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(SERVICE_TOKEN_HEADER, "OtherServiceCredential_0123456789_abcdef"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(CONTACT_DECISIONS).contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(SERVICE_TOKEN_HEADER, CHAT_CONTRACT_TOKEN, "extra"))
                .andExpect(status().isUnauthorized());

        var allowed = mvc.perform(post(CONTACT_DECISIONS).contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(SERVICE_TOKEN_HEADER, CHAT_CONTRACT_TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.actorId").value(Long.toString(actor)))
                .andExpect(jsonPath("$.peerId").value(Long.toString(peer)))
                .andExpect(jsonPath("$.purpose").value("DIRECT_SEND"))
                .andExpect(jsonPath("$.operationId").value(operation.toString()))
                .andExpect(jsonPath("$.mutual").value(true))
                .andExpect(jsonPath("$.contactAllowed").value(true)).andReturn();
        var decision = new ObjectMapper().readTree(allowed.getResponse().getContentAsString());
        assertThat(Duration.between(Instant.parse(decision.get("evaluatedAt").asText()),
                Instant.parse(decision.get("expiresAt").asText()))).isEqualTo(Duration.ofSeconds(2));

        contacts.setBlocked(peer, actor, true);
        mvc.perform(post(CONTACT_DECISIONS).contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(SERVICE_TOKEN_HEADER, CHAT_CONTRACT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactAllowed").value(false))
                .andExpect(jsonPath("$.blockedByActor").value(false));
    }

    @Test
    void privateProfileBatchOnlyReturnsRequestedPublicFieldsAndExplicitMissingIds() throws Exception {
        long actor = user(), peer = user();
        String payload = """
                {"userIds":["%d","%d","%d","%d"]}
                """.formatted(peer, Long.MAX_VALUE, actor, peer);
        mvc.perform(post(PROFILE_BATCH).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PROFILE_BATCH).contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(SERVICE_TOKEN_HEADER, CHAT_CONTRACT_TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.profiles.length()").value(2))
                .andExpect(jsonPath("$.profiles[0].id").value(Long.toString(peer)))
                .andExpect(jsonPath("$.profiles[0].username").isString())
                .andExpect(jsonPath("$.profiles[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.profiles[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.profiles[1].id").value(Long.toString(actor)))
                .andExpect(jsonPath("$.missingIds[0]").value(Long.toString(Long.MAX_VALUE)));
        mvc.perform(post(PROFILE_BATCH).contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[\"-1\"]}")
                        .header(SERVICE_TOKEN_HEADER, CHAT_CONTRACT_TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDecisionThatExpiresBeforeCommitRollsBackTheMessageAndQuota() {
        long actor = user(), peer = user();
        var request = text("Expire the remote permission during this transaction");
        AtomicReference<Instant> expiry = new AtomicReference<>();
        doAnswer(call -> {
            expiry.set(Instant.now().plusMillis(1500));
            return Map.of(peer, new Decision(false, true, false, expiry.get()));
        }).when(directory).decisions(actor, List.of(peer), Purpose.DIRECT_SEND, request.clientMessageId());
        doAnswer(call -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE sender_id = ?", Long.class, actor))
                    .isEqualTo(1);
            Duration untilExpiry = Duration.between(Instant.now(), expiry.get());
            if (!untilExpiry.isNegative()) Thread.sleep(untilExpiry.plusMillis(20));
            return call.callRealMethod();
        }).when(directory).displayNames(List.of(actor));

        expectStatus(() -> chat.sendDirect(actor, peer, request), HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE sender_id = ?", Long.class, actor)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_daily_usage WHERE user_id = ?", Long.class, actor)).isZero();
        assertThat(chat.detail(actor, peer).id()).isNull();
    }

    @BeforeAll
    void migrate() {
        if (MYSQL_URL.isBlank()) {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/mysql/V20261120_1__create_chat.sql"));
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/mysql/V20261120_2__add_chat_history_revision.sql"));
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/mysql/V20261124_1__chat_message_recall.sql"));
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/mysql/V20261124_2__social_friend_requests.sql"));
                return null;
            });
        }
    }

    @Test
    void recallKeepsSequenceAndEvidenceAndOnlyLetsTheSenderActForTwoMinutes() throws Exception {
        long sender = user(), recipient = user();
        follows.follow(sender, recipient);
        follows.follow(recipient, sender);
        var firstRequest = text("Keep as original evidence after recall");
        var first = chat.sendDirect(sender, recipient, firstRequest);
        long room = first.conversationId();
        long message = first.message().id();
        String path = "/api/v1/chat/conversations/" + room + "/messages/" + message;
        String actorToken = "Bearer " + jwt.generateToken(sender, "chat-test");
        String recipientToken = "Bearer " + jwt.generateToken(recipient, "chat-test");
        mvc.perform(delete(path).header("Authorization", recipientToken)).andExpect(status().isNotFound());

        long previousRevision = chat.history(recipient, room, null, null).revision();
        mvc.perform(delete(path).header("Authorization", actorToken)).andExpect(status().isNoContent());
        mvc.perform(delete(path).header("Authorization", actorToken)).andExpect(status().isNoContent());
        var history = chat.history(recipient, room, null, null);
        assertThat(history.revision()).isEqualTo(previousRevision + 1);
        assertThat(history.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(message);
            assertThat(item.seq()).isEqualTo(first.message().seq());
            assertThat(item.content()).isNull();
            assertThat(item.recalled()).isTrue();
        });
        assertThat(chat.sendDirect(sender, recipient, firstRequest).message().content()).isNull();
        assertThat(chat.inbox(recipient, false, 1, 10)).singleElement().satisfies(preview -> {
            assertThat(preview.preview()).isEqualTo("消息已撤回");
            assertThat(preview.unread()).isFalse();
        });
        assertThat(jdbc.queryForObject("SELECT body FROM chat_messages WHERE id=?", String.class, message))
                .isEqualTo("Keep as original evidence after recall");

        var old = chat.sendDirect(sender, recipient, text("Outside recall window")).message();
        jdbc.update("UPDATE chat_messages SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(121)), old.id());
        mvc.perform(delete("/api/v1/chat/conversations/" + room + "/messages/" + old.id())
                        .header("Authorization", actorToken)).andExpect(status().isConflict());
        assertThat(chat.history(recipient, room, null, null).items().getLast().content())
                .isEqualTo("Outside recall window");
    }

    @Test
    void acceptedFriendRequestGrantsChatAccessWithoutMutualFollowAndHonorsBlocking() throws Exception {
        long sender = user(), recipient = user(), outsider = user();
        String prefix = "/api/v1/chat/friend-requests";
        String senderToken = "Bearer " + jwt.generateToken(sender, "chat-test");
        String recipientToken = "Bearer " + jwt.generateToken(recipient, "chat-test");
        String outsiderToken = "Bearer " + jwt.generateToken(outsider, "chat-test");
        String accept = "{\"accept\":true}";
        mvc.perform(put(prefix + "/" + recipient).header("Authorization", senderToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        mvc.perform(put(prefix + "/" + recipient).header("Authorization", senderToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        mvc.perform(get(prefix + "/incoming").header("Authorization", recipientToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(prefix + "/incoming").header("Authorization", outsiderToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(put(prefix + "/" + recipient + "/decision").header("Authorization", senderToken)
                        .contentType(MediaType.APPLICATION_JSON).content(accept))
                .andExpect(status().isNotFound());
        mvc.perform(put(prefix + "/" + sender + "/decision").header("Authorization", recipientToken)
                        .contentType(MediaType.APPLICATION_JSON).content(accept))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FRIEND"));

        assertThat(follows.areMutual(sender, recipient)).isFalse();
        assertThat(contacts.friends(sender, 1, 30).getTotalElements()).isEqualTo(1);
        assertThat(chat.sendDirect(sender, recipient, text("Friends can send without mutual follow")).state())
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_daily_usage WHERE user_id=?", Long.class, sender))
                .isZero();
        assertThat(chat.createGroup(sender, new Group("Friends group", List.of(recipient)))).isPositive();
        contacts.setBlocked(recipient, sender, true);
        assertThat(contacts.friends(sender, 1, 30).getTotalElements()).isZero();
        expectStatus(() -> chat.sendDirect(sender, recipient, text("Now blocked")), HttpStatus.FORBIDDEN);
    }

    @Test
    void unfriendPreservesAnExistingOneWayFollowAndAllowsAnotherRequest() {
        long actor = user(), peer = user();
        follows.follow(actor, peer);
        contacts.requestFriend(actor, peer);
        contacts.decideFriend(peer, actor, true);

        assertThat(contacts.removeFriend(actor, peer)).isEqualTo(FriendStatus.NONE);
        assertThat(contacts.removeFriend(actor, peer)).isEqualTo(FriendStatus.NONE);
        assertThat(follows.status(actor, peer).isFollowedByMe()).isTrue();
        assertThat(contacts.friends(actor, 1, 30)).isEmpty();
        assertThat(contacts.friendStatus(peer, actor)).isEqualTo(FriendStatus.NONE);
        expectStatus(() -> contacts.decideFriend(peer, actor, true), HttpStatus.NOT_FOUND);
        assertThat(contacts.requestFriend(peer, actor)).isEqualTo(FriendStatus.SENT);
        assertThat(contacts.decideFriend(actor, peer, true)).isEqualTo(FriendStatus.FRIEND);
    }

    @Test
    void unfriendBreaksBothFriendshipSourcesAndKeepsTheOtherUsersFollowAndChatHistory() throws Exception {
        long actor = user(), peer = user();
        contacts.requestFriend(actor, peer);
        contacts.decideFriend(peer, actor, true);
        follows.follow(actor, peer);
        follows.follow(peer, actor);
        var original = chat.sendDirect(actor, peer, text("Before removing friendship"));
        String authorization = "Bearer " + jwt.generateToken(actor, "chat-test");
        String path = "/api/v1/chat/friends/";
        mvc.perform(delete(path + peer)).andExpect(status().isUnauthorized());
        mvc.perform(delete(path + actor).header("Authorization", authorization)).andExpect(status().isBadRequest());

        mvc.perform(delete(path + peer).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NONE"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        assertThat(follows.status(actor, peer).isFollowedByMe()).isFalse();
        assertThat(follows.status(actor, peer).isFollowingMe()).isTrue();
        assertThat(contacts.friends(peer, 1, 30)).isEmpty();
        var next = chat.sendDirect(actor, peer, text("After removing friendship"));
        assertThat(next.conversationId()).isEqualTo(original.conversationId());
        assertThat(next.quota().remaining()).isEqualTo(9);
        assertThat(chat.history(peer, original.conversationId(), null, null).items())
                .extracting(Message::content).containsExactly("Before removing friendship", "After removing friendship");
    }

    @Test
    void newContactDecisionWaitsForAnUnfriendTransactionToCommit() throws Exception {
        long actor = user(), peer = user();
        follows.follow(actor, peer);
        follows.follow(peer, actor);
        var removed = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var decisionStarted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var removal = executor.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                var result = contacts.removeFriend(actor, peer);
                removed.countDown();
                assertThat(assertDoesNotThrow(() -> allowCommit.await(5, TimeUnit.SECONDS))).isTrue();
                return result;
            }));
            try {
                assertThat(removed.await(5, TimeUnit.SECONDS)).isTrue();
                var decision = executor.submit(() -> {
                    decisionStarted.countDown();
                    return contacts.evaluateForChat(actor, peer).decision();
                });
                assertThat(decisionStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> decision.get(350, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCommit.countDown();
                assertThat(removal.get(5, TimeUnit.SECONDS)).isEqualTo(FriendStatus.NONE);
                assertThat(decision.get(5, TimeUnit.SECONDS).friend()).isFalse();
            } finally {
                allowCommit.countDown();
            }
        }
    }

    @Test
    void chatReadsCurrentDisplayNamesAndKeepsHistoryWhenTheSenderProfileIsMissing() {
        long sender = user(), recipient = user();
        String recipientUsername = users.findById(recipient).orElseThrow().getUsername();
        var profile = new ProfileDto();
        profile.setNickname("Sender nickname");
        profiles.updateProfile(sender, profile);

        assertThat(chat.detail(sender, recipient).peer()).isEqualTo(new Person(recipient, recipientUsername));
        var request = text("Profile read");
        var sent = chat.sendDirect(sender, recipient, request);
        assertThat(sent.message().sender()).isEqualTo(new Person(sender, "Sender nickname"));
        assertThat(chat.inbox(recipient, true, 1, 10)).singleElement()
                .satisfies(row -> assertThat(row.title()).isEqualTo("Sender nickname"));

        follows.follow(sender, recipient);
        follows.follow(recipient, sender);
        long group = chat.createGroup(sender, new Group("Group title", List.of(recipient)));
        assertThat(chat.members(sender, group)).anySatisfy(member ->
                assertThat(member.user()).isEqualTo(new Person(recipient, recipientUsername)));

        profile.setNickname("Updated nickname");
        profiles.updateProfile(sender, profile);
        assertThat(chat.sendDirect(sender, recipient, request).message().sender().name()).isEqualTo("Updated nickname");
        contacts.setBlocked(recipient, sender, true);
        assertThat(contacts.blockedContacts(recipient)).containsExactly(new BlockedContact(sender, "Updated nickname"));

        var retiredAccounts = LongStream.rangeClosed(Long.MAX_VALUE - 500, Long.MAX_VALUE - 1).boxed().toList();
        jdbc.batchUpdate("INSERT INTO chat_blocks (blocker_id, blocked_id) VALUES (?, ?)", retiredAccounts, 500,
                (statement, id) -> {
                    statement.setLong(1, recipient);
                    statement.setLong(2, id);
                });
        var blocks = contacts.blockedContacts(recipient);
        assertThat(blocks).hasSize(501);
        assertThat(blocks.getFirst()).isEqualTo(new BlockedContact(sender, "Updated nickname"));
        assertThat(blocks.getLast()).isEqualTo(new BlockedContact(retiredAccounts.getLast(), "用户"));

        long missingSender = Long.MAX_VALUE;
        jdbc.update("UPDATE chat_messages SET sender_id = ? WHERE id = ?", missingSender, sent.message().id());
        assertThat(chat.history(recipient, sent.conversationId(), null, null).items()).singleElement().satisfies(message -> {
            assertThat(message.sender()).isEqualTo(new Person(missingSender, "用户"));
            assertThat(message.content()).isEqualTo("Profile read");
        });
        expectStatus(() -> chat.detail(recipient, missingSender), HttpStatus.NOT_FOUND);
    }

    @Test
    void requestConsentPreferencesAndBlockApplyToPrivateMessages() {
        long a = user(), b = user(), outsider = user();
        chat.preferences(b, false);
        expectStatus(() -> chat.sendDirect(a, b, text("无人能提交请求")), HttpStatus.FORBIDDEN);
        assertThat(chat.inbox(b, true, 1, 10)).isEmpty();
        chat.preferences(b, true);
        Send request = text("首条请求");
        var sent = chat.sendDirect(a, b, request);
        long id = sent.conversationId();
        assertThat(sent.state()).isEqualTo("PENDING");
        assertThat(sent.quota().remaining()).isEqualTo(9);
        assertThat(chat.sendDirect(a, b, request).message().id()).isEqualTo(sent.message().id());
        expectStatus(() -> chat.sendDirect(a, b, text("不能追加")), HttpStatus.CONFLICT);
        expectStatus(() -> chat.decide(a, id, true), HttpStatus.NOT_FOUND);
        expectStatus(() -> chat.history(outsider, id, null, null), HttpStatus.NOT_FOUND);
        assertThat(chat.inbox(b, true, 1, 10).getContent()).singleElement().satisfies(row -> {
            assertThat(row.unread()).isTrue();
            assertThat(row.preview()).isEqualTo("首条请求");
        });
        chat.read(b, id, sent.message().seq());
        assertThat(chat.inbox(b, true, 1, 10).getContent().getFirst().unread()).isFalse();
        assertThat(chat.decide(b, id, true).state()).isEqualTo("ACTIVE");
        assertThat(chat.sendDirect(b, a, text("你好")).quota().remaining()).isEqualTo(9);
        contacts.setBlocked(b, a, true);
        expectStatus(() -> chat.sendDirect(a, b, text("拦截")), HttpStatus.FORBIDDEN);
        assertThat(chat.history(b, id, null, null).items()).hasSize(2);
        contacts.setBlocked(b, a, false);
        assertThat(chat.sendDirect(a, b, text("继续")).quota().remaining()).isEqualTo(8);
    }

    @Test
    void mutualFollowingIsDerivedAndUnfollowUsesQuotaForTheExistingRoom() {
        long a = user(), b = user();
        follows.follow(a, b);
        assertThat(contacts.friends(a, 1, 20)).isEmpty();
        follows.follow(b, a);
        assertThat(contacts.friends(a, 1, 20).getContent()).extracting(summary -> summary.getId()).containsExactly(b);
        chat.preferences(b, false);
        var initial = chat.sendDirect(a, b, text("好友消息"));
        assertThat(initial.state()).isEqualTo("ACTIVE");
        assertThat(initial.quota().remaining()).isEqualTo(10);
        follows.unfollow(a, b);
        var next = chat.sendDirect(a, b, text("非好友消息"));
        assertThat(next.conversationId()).isEqualTo(initial.conversationId());
        assertThat(next.quota().remaining()).isEqualTo(9);
        follows.follow(a, b);
        contacts.setBlocked(b, a, true);
        assertThat(contacts.friends(a, 1, 20)).isEmpty();
    }

    @Test
    void publicFriendsAndBlockRoutesKeepAuthenticationAndResponseShape() throws Exception {
        long actor = user(), peer = user();
        follows.follow(actor, peer);
        follows.follow(peer, actor);
        String token = "Bearer " + jwt.generateToken(actor, "chat-test");
        String peerToken = "Bearer " + jwt.generateToken(peer, "chat-test");
        String blocks = "/api/v1/chat/blocks";
        mvc.perform(get(blocks)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/chat/friends").param("size", "1").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.items[0].id").value(peer)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(put(blocks + "/" + actor).header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(put(blocks + "/0").header("Authorization", token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("消息请求参数无效"));
        mvc.perform(put(blocks + "/" + peer).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(get(blocks).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$[0].id").value(peer)).andExpect(jsonPath("$[0].name").isString());
        mvc.perform(get(blocks).header("Authorization", peerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete(blocks + "/" + peer).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(get(blocks).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void concurrentSendsToDifferentPeersShareTheFinalDailySlot() throws Exception {
        long a = user(), b = user(), c = user();
        long first = chat.sendDirect(a, b, text("请求一")).conversationId();
        long second = chat.sendDirect(a, c, text("请求二")).conversationId();
        chat.decide(b, first, true);
        chat.decide(c, second, true);
        for (int i = 0; i < 7; i++) chat.sendDirect(a, b, text("同日消息 " + i));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(sendAt(start, a, b));
            var right = pool.submit(sendAt(start, a, c));
            start.countDown();
            assertThat(List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var quota = chat.detail(a, c).quota();
        assertThat(quota.remaining()).isZero();
        assertThat(quota.resetAt().atZone(ZoneId.of("Asia/Shanghai")).getHour()).isZero();
        assertThat(chat.history(a, first, null, null).items().size()
                + chat.history(a, second, null, null).items().size()).isEqualTo(10);
    }

    @Test
    void bulkGroupInviteWaitsForAConcurrentBlockBeforeCheckingAllPeers() throws Exception {
        long owner = user(), first = user(), second = user();
        for (long peer : List.of(first, second)) {
            follows.follow(owner, peer);
            follows.follow(peer, owner);
        }
        try (var pool = Executors.newSingleThreadExecutor()) {
            var invite = new TransactionTemplate(transactionManager).execute(transaction -> {
                contacts.setBlocked(second, owner, true);
                var started = new CountDownLatch(1);
                var result = pool.submit(() -> {
                    started.countDown();
                    expectStatus(() -> chat.createGroup(owner, new Group("Concurrent invite", List.of(second, first))),
                            HttpStatus.FORBIDDEN);
                });
                assertThat(assertDoesNotThrow(() -> started.await(5, TimeUnit.SECONDS))).isTrue();
                assertThatThrownBy(() -> result.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                return result;
            });
            invite.get(10, TimeUnit.SECONDS);
        }
        assertThat(chat.inbox(first, true, 1, 10)).isEmpty();
        assertThat(chat.inbox(second, true, 1, 10)).isEmpty();
    }

    @Test
    void groupInvitationLeaveAndRejoinEnforceTheVisibleSequence() {
        long a = user(), b = user(), outsider = user();
        follows.follow(a, b);
        follows.follow(b, a);
        long id = chat.createGroup(a, new Group("摄影交流", List.of(b)));
        chat.sendGroup(a, id, text("入群前"));
        assertThat(chat.inbox(b, true, 1, 10).getContent()).singleElement().satisfies(row -> {
            assertThat(row.membership()).isEqualTo("INVITED");
            assertThat(row.preview()).isNull();
        });
        expectStatus(() -> chat.history(b, id, null, null), HttpStatus.NOT_FOUND);
        expectStatus(() -> chat.members(outsider, id), HttpStatus.NOT_FOUND);
        chat.join(b, id, true);
        chat.sendGroup(a, id, text("加入之后"));
        assertThat(chat.history(b, id, null, null).items()).extracting(Message::content).containsExactly("加入之后");
        expectStatus(() -> chat.remove(a, id, a), HttpStatus.CONFLICT);
        chat.remove(b, id, b);
        chat.sendGroup(a, id, text("离开期间"));
        expectStatus(() -> chat.history(b, id, null, 0L), HttpStatus.NOT_FOUND);
        chat.invite(a, id, b);
        chat.join(b, id, true);
        var beforeRejoinMessage = chat.history(b, id, null, 3L);
        assertThat(beforeRejoinMessage.visibleFromSeq()).isEqualTo(4);
        assertThat(beforeRejoinMessage.items()).isEmpty();
        var sent = chat.sendGroup(a, id, text("重新加入"));
        assertThat(chat.history(b, id, null, 0L).items()).extracting(Message::content).containsExactly("重新加入");
        var report = new CreateReportRequest();
        report.setTargetType(ReportTargetType.CHAT_MESSAGE);
        report.setTargetId(sent.id());
        report.setReason(ReportReason.OTHER);
        expectStatus(() -> reports.create(outsider, report), HttpStatus.NOT_FOUND);
        assertThat(reports.create(b, report).getChatEvidence()).isNull();
        chat.transfer(a, id, b);
        expectStatus(() -> chat.close(a, id), HttpStatus.FORBIDDEN);
        chat.close(b, id);
        expectStatus(() -> chat.history(a, id, null, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void moderatorsCannotInspectAnUnrelatedPrivateReportAndAdminsSeeOnlyReportedEvidence() {
        long a = user(), b = user(), moderator = user(), admin = user();
        governance.bootstrapAdmin(admin);
        Long section = jdbc.queryForObject("SELECT MIN(id) FROM sections", Long.class);
        governance.setModeratorSections(admin, moderator, List.of(section), "测试分区授权");
        var sent = chat.sendDirect(a, b, text("被举报的私信"));
        var request = new CreateReportRequest();
        request.setTargetType(ReportTargetType.CHAT_MESSAGE);
        request.setTargetId(sent.message().id());
        request.setReason(ReportReason.OTHER);
        long reportId = reports.create(b, request).getId();
        assertThat(reports.listForReviewer(moderator, ReportStatus.PENDING, 1, 50))
                .noneMatch(report -> report.getId() == reportId);
        assertThat(reports.listForReviewer(admin, ReportStatus.PENDING, 1, 50)).anySatisfy(report -> {
            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getChatEvidence()).isEqualTo("被举报的私信");
        });
        var hide = new ResolveReportRequest();
        hide.setAction(ReportAction.HIDE);
        hide.setReason("测试审核");
        expectStatus(() -> reports.resolve(moderator, reportId, hide), HttpStatus.NOT_FOUND);
        assertThat(chat.history(b, sent.conversationId(), null, 1L).revision()).isZero();
        reports.resolve(admin, reportId, hide);
        assertThat(chat.history(b, sent.conversationId(), null, 1L).revision()).isEqualTo(1);
        assertThat(chat.history(b, sent.conversationId(), null, null).items().getFirst().content()).isNull();

        var restore = new ResolveReportRequest();
        restore.setAction(ReportAction.RESTORE);
        restore.setReason("Restored after review");
        reports.resolve(admin, reportId, restore);
        assertThat(chat.history(b, sent.conversationId(), null, 1L).revision()).isEqualTo(2);
        assertThat(chat.history(b, sent.conversationId(), null, null).items().getFirst().content()).isEqualTo(sent.message().content());
    }

    @Test
    void groupMembershipTracksInvitationRevocationAndExpiry() {
        long owner = user(), invitee = user(), outsider = user();
        follows.follow(owner, invitee);
        follows.follow(invitee, owner);
        long id = chat.createGroup(owner, new Group("Membership", List.of(invitee)));
        assertThat(chat.groupMembership(invitee, id).state()).isEqualTo("INVITED");
        assertThat(chat.groupMembership(invitee, id).ownerId()).isEqualTo(owner);
        expectStatus(() -> chat.groupMembership(outsider, id), HttpStatus.NOT_FOUND);

        chat.remove(owner, id, invitee);
        expectStatus(() -> chat.groupMembership(invitee, id), HttpStatus.NOT_FOUND);
        chat.invite(owner, id, invitee);
        jdbc.update("UPDATE chat_members SET invited_at = ? WHERE conversation_id = ? AND user_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(8 * 24 * 60 * 60)), id, invitee);
        expectStatus(() -> chat.groupMembership(invitee, id), HttpStatus.NOT_FOUND);
        chat.invite(owner, id, invitee);
        chat.join(invitee, id, true);
        assertThat(chat.groupMembership(invitee, id).state()).isEqualTo("JOINED");
    }

    @Test
    void groupHistoryReturnsAllThirtyOneMessagesAfterTheReadCursor() {
        long sender = user(), recipient = user();
        follows.follow(sender, recipient);
        follows.follow(recipient, sender);
        long id = chat.createGroup(sender, new Group("Backlog", List.of(recipient)));
        chat.join(recipient, id, true);
        long cursor = chat.sendGroup(sender, id, text("Last received message")).seq();
        chat.read(recipient, id, cursor);
        for (int i = 1; i <= 31; i++) {
            chat.sendGroup(sender, id, text("Pending message " + i));
        }

        var first = chat.history(recipient, id, null, cursor);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items()).extracting(Message::seq)
                .containsExactlyElementsOf(LongStream.rangeClosed(cursor + 1, cursor + 30).boxed().toList());

        var second = chat.history(recipient, id, null, first.items().getLast().seq());
        assertThat(second.hasMore()).isFalse();
        assertThat(second.items()).extracting(Message::seq).containsExactly(cursor + 31);
    }

    private Callable<Boolean> sendAt(CountDownLatch start, long sender, long peer) {
        return () -> {
            start.await();
            try {
                chat.sendDirect(sender, peer, text("并发消息"));
                return true;
            } catch (ChatService.QuotaExceeded expected) {
                return false;
            }
        };
    }

    private long user() {
        User user = new User();
        user.setUsername("chat-" + UUID.randomUUID());
        user.setPasswordHash("test-only");
        return users.save(user).getId();
    }

    private static Send text(String body) {
        return new Send(UUID.randomUUID(), body);
    }

    private static void expectStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
}
