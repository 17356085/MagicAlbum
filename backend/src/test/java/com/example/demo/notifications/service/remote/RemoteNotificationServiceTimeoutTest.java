package com.example.demo.notifications.service.remote;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteNotificationServiceTimeoutTest {
    @Test
    void stalled_response_is_bounded_and_exposed_as_service_unavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/api/v1/notifications", exchange -> {
            received.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new RemoteNotificationService("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-credential-0123456789abcdefghijklmnop", 500, 200);
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.list(1L, "Bearer test", null, null, 1, 10))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void unbounded_or_invalid_timeouts_fail_configuration() {
        for (long value : new long[]{0, -1, 10001}) {
            assertThatThrownBy(() -> new RemoteNotificationService("http://localhost", "test", value, 500))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RemoteNotificationService("http://localhost", "test", 500, value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
