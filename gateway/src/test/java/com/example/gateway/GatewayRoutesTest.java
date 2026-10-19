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
        assertThat(source.getProperty(routes + "[0].metadata.response-timeout")).isEqualTo(65000);
        assertThat(source.getProperty(routes + "[1].id")).isEqualTo("notification-service-api");
        assertThat(source.getProperty(routes + "[1].predicates[0]")).isEqualTo("Path=/api/v1/notifications/**");
        assertThat(source.getProperty(routes + "[1].metadata.response-timeout")).isNull();
        assertThat(source.getProperty(routes + "[2].id")).isEqualTo("bluealbum-ai-api");
        assertThat(source.getProperty(routes + "[2].predicates[0]")).isEqualTo("Path=/api/v1/ai/**");
        assertThat(source.getProperty(routes + "[2].metadata.response-timeout")).isEqualTo(120000);
        assertThat(source.getProperty(routes + "[3].id")).isEqualTo("bluealbum-monolith-api");
        assertThat(source.getProperty(routes + "[3].predicates[0]")).isEqualTo("Path=/api/v1/**,/uploads/**");
    }
}
