package com.example.demo.ai.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class RealAiSummaryTransportTest {
    @Test void successfulSummaryRequiresRealContentAndHttpFailureIsNotSuccessfulText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean fail = request.contains("failure-input");
            byte[] body = (fail ? "sensitive-provider-error" : "{\"choices\":[{\"message\":{\"content\":\"actual summary\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var provider = new RealAiProvider("http://127.0.0.1:" + server.getAddress().getPort(), "test-only", "test", Duration.ofSeconds(2));
            assertThat(provider.generateSummary("good-input")).isEqualTo("actual summary");
            assertThatThrownBy(() -> provider.generateSummary("failure-input"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("AI summary generation failed")
                    .hasNoCause();
        } finally { server.stop(0); }
    }

    @Test void blockedResponseHasAnActualRequestDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try { java.lang.Thread.sleep(600); } catch (InterruptedException ex) { java.lang.Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var provider = new RealAiProvider("http://127.0.0.1:" + server.getAddress().getPort(), "test-only", "test", Duration.ofMillis(100));
            long start = System.nanoTime();
            assertThatThrownBy(() -> provider.generateSummary("body")).isInstanceOf(IllegalStateException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally { server.stop(0); }
    }
}
