package com.example.demo.chat;

import com.example.demo.chat.remote.RemoteChatDirectory;
import com.example.demo.chat.service.ChatDirectory.Decision;
import com.example.demo.chat.service.ChatDirectory.Purpose;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class RemoteChatDirectoryTest {
    private static final String TOKEN = "ChatServiceTestCredential_0123456789_abcdef";
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void locksDistinctBucketsInAscendingOrderWithoutCallingCore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RemoteChatDirectory directory = new RemoteChatDirectory(jdbc, "http://127.0.0.1:1", TOKEN);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            directory.lockAccounts(List.of(257L, 2L, 1L, 257L));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        var calls = inOrder(jdbc);
        calls.verify(jdbc).update(anyString(), eq(1));
        calls.verify(jdbc).update(anyString(), eq(2));
        calls.verifyNoMoreInteractions();
    }

    @Test
    void profileBatchUsesStringIdsAndReturnsOnlyAuthorizedRequestedProfiles() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"profiles":[{"id":"1","username":"one","nickname":"昵称","avatarUrl":"https://example.invalid/a"}],
                     "missingIds":["2"]}
                    """);
        });
        RemoteChatDirectory directory = new RemoteChatDirectory(mock(JdbcTemplate.class), baseUrl(), TOKEN);

        assertThat(directory.displayNames(List.of(1L, 2L)))
                .containsExactly(Map.entry(1L, "昵称"));
        assertThat(body).hasValue("{\"userIds\":[\"1\",\"2\"]}");
    }

    @Test
    void contactDecisionBatchUsesOneRequestAndPreservesPeerOrder() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> uri = new AtomicReference<>();
        UUID operation = UUID.randomUUID();
        server = server(exchange -> {
            requests.incrementAndGet();
            uri.set(exchange.getRequestURI().getPath());
            Instant evaluated = Instant.now();
            respond(exchange, 200, """
                    {"decisions":[
                      {"decisionId":"%s","actorId":"7","peerId":"8","purpose":"GROUP_INVITE","operationId":"%s",
                       "mutual":true,"contactAllowed":true,"blockedByActor":false,"evaluatedAt":"%s","expiresAt":"%s"},
                      {"decisionId":"%s","actorId":"7","peerId":"9","purpose":"GROUP_INVITE","operationId":"%s",
                       "mutual":false,"contactAllowed":true,"blockedByActor":false,"evaluatedAt":"%s","expiresAt":"%s"}
                    ]}
                    """.formatted(UUID.randomUUID(), operation, evaluated, evaluated.plusSeconds(2),
                    UUID.randomUUID(), operation, evaluated, evaluated.plusSeconds(2)));
        });
        RemoteChatDirectory directory = new RemoteChatDirectory(mock(JdbcTemplate.class), baseUrl(), TOKEN);

        Map<Long, Decision> decisions = directory.decisions(7L, List.of(8L, 9L), Purpose.GROUP_INVITE, operation);

        assertThat(requests).hasValue(1);
        assertThat(uri).hasValue("/internal/v1/social/contact-decisions:batchGet");
        assertThat(decisions.keySet()).containsExactly(8L, 9L);
        assertThat(decisions.get(8L).mutual()).isTrue();
        assertThat(decisions.get(9L).mutual()).isFalse();
    }

    @Test
    void malformedDecisionEchoIsUnavailable() throws Exception {
        UUID operation = UUID.randomUUID();
        Instant evaluated = Instant.now();
        server = server(exchange -> respond(exchange, 200, """
                {"decisionId":"%s","actorId":"7","peerId":"999","purpose":"DIRECT_SEND","operationId":"%s",
                 "mutual":true,"contactAllowed":true,"blockedByActor":false,"evaluatedAt":"%s","expiresAt":"%s"}
                """.formatted(UUID.randomUUID(), operation, evaluated, evaluated.plusSeconds(2))));
        RemoteChatDirectory directory = new RemoteChatDirectory(mock(JdbcTemplate.class), baseUrl(), TOKEN);

        assertThatThrownBy(() -> directory.decisions(7L, List.of(8L), Purpose.DIRECT_SEND, operation))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(503));
    }

    @Test
    void bearerVerificationSendsFullHeaderAndMapsCoreRejectionToNull() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        RemoteChatDirectory directory = new RemoteChatDirectory(mock(JdbcTemplate.class), baseUrl(), TOKEN);

        assertThat(directory.verifyBearer("Bearer complete-token")).isNull();
        assertThat(authorization).hasValue("Bearer complete-token");
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        value.start();
        return value;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
