package com.example.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTimeoutTest {
    private static final DisposableServer DOWNSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> response.sendString(Mono.delay(Duration.ofSeconds(2)).map(tick -> "late")))
            .bindNow();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry properties) {
        String prefix = "spring.cloud.gateway.server.webflux.";
        properties.add(prefix + "httpclient.connect-timeout", () -> 500);
        properties.add(prefix + "httpclient.response-timeout", () -> "200ms");
        properties.add(prefix + "routes[0].id", () -> "timeout-test");
        properties.add(prefix + "routes[0].uri", () -> "http://127.0.0.1:" + DOWNSTREAM.port());
        properties.add(prefix + "routes[0].predicates[0]", () -> "Path=/slow");
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @Test
    void actual_gateway_returns_504_for_a_stalled_downstream() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/slow"))
                .timeout(Duration.ofSeconds(5)).header("X-Request-Id", "timeout-check").GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.headers().firstValue("X-Request-Id")).contains("timeout-check");
        assertThat(response.body()).doesNotContain("late");
    }
}
