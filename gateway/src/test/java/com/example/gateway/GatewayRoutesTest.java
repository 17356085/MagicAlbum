package com.example.gateway;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutesTest {
    @ParameterizedTest
    @ValueSource(strings = {"local", "compose", "cloud"})
    void profiles_route_notifications_first_and_include_uploaded_images(String profile) throws Exception {
        var source = new YamlPropertySourceLoader().load(profile,
                new ClassPathResource("application-" + profile + ".yml")).getFirst();
        String routes = "spring.cloud.gateway.server.webflux.routes";

        assertThat(source.getProperty(routes + "[0].id")).isEqualTo("notification-service-stream");
        assertThat(source.getProperty(routes + "[0].predicates[0]")).isEqualTo("Path=/api/v1/notifications/stream");
        assertThat(source.getProperty(routes + "[0].predicates[1]")).isEqualTo("Method=GET,HEAD");
        assertThat(source.getProperty(routes + "[0].metadata.response-timeout")).isEqualTo(65000);
        assertThat(source.getProperty(routes + "[1].id")).isEqualTo("notification-service-api");
        assertThat(source.getProperty(routes + "[1].predicates[0]")).isEqualTo(
                "Path=/api/v1/notifications,/api/v1/notifications/events");
        assertThat(source.getProperty(routes + "[1].predicates[1]")).isEqualTo("Method=GET,HEAD");
        assertThat(source.getProperty(routes + "[1].metadata.response-timeout")).isNull();
        assertThat(source.getProperty(routes + "[2].id")).isEqualTo("bluealbum-ai-api");
        assertThat(source.getProperty(routes + "[2].predicates[0]")).isEqualTo("Path=/api/v1/ai/**");
        assertThat(source.getProperty(routes + "[2].metadata.response-timeout")).isEqualTo(120000);
        assertThat(source.getProperty(routes + "[3].id")).isEqualTo("bluealbum-monolith-api");
        assertThat(source.getProperty(routes + "[3].predicates[0]")).isEqualTo("Path=/api/v1/**,/uploads/**");
        assertThat(source.getProperty(routes + "[4].id")).isEqualTo("chat-realtime");
        assertThat(source.getProperty(routes + "[4].predicates[0]")).isEqualTo("Path=/api/v1/chat/realtime");
        assertThat(source.getProperty(routes + "[4].order")).isEqualTo(-1);
        assertThat(source.getProperty(routes + "[5].id")).isEqualTo("notification-service-read");
        assertThat(source.getProperty(routes + "[5].predicates[0]")).isEqualTo(
                "Path=/api/v1/notifications/{id}/read");
        assertThat(source.getProperty(routes + "[5].predicates[1]")).isEqualTo("Method=PATCH");
        assertThat(source.getProperty(routes + "[5].order")).isEqualTo(-1);
        assertThat(source.getProperty(routes + "[6].id")).isEqualTo("chat-service-api");
        assertThat(source.getProperty(routes + "[6].predicates[0]")).isEqualTo(
                "Path=/api/v1/chat/conversations/**,/api/v1/chat/direct/**,/api/v1/chat/groups/**,/api/v1/chat/settings");
        assertThat(source.getProperty(routes + "[6].order")).isEqualTo(-2);
        assertThat(source.getProperty(routes + "[6].uri")).isEqualTo(coreUri(profile));
        for (int index = 0; index <= 6; index++) {
            assertThat(String.valueOf(source.getProperty(routes + "[" + index + "].predicates[0]")))
                    .doesNotContain("/internal");
        }
    }

    private static String coreUri(String profile) {
        return switch (profile) {
            case "local" -> "${CHAT_SERVICE_GATEWAY_URI:http://127.0.0.1:8080}";
            case "compose" -> "${CHAT_SERVICE_GATEWAY_URI:http://backend:8080}";
            case "cloud" -> "${CHAT_SERVICE_GATEWAY_URI:lb://bluealbum-monolith}";
            default -> throw new IllegalArgumentException("Unexpected profile: " + profile);
        };
    }
}
