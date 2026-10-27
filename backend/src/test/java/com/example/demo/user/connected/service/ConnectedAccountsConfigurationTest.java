package com.example.demo.user.connected.service;

import com.example.demo.user.connected.service.mock.InMemoryConnectedAccountsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectedAccountsConfigurationTest {
    @Test
    void uses_memory_when_repository_is_absent() {
        new ApplicationContextRunner().withUserConfiguration(ConnectedAccountsConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConnectedAccountsService.class);
                    assertThat(context.getBean(ConnectedAccountsService.class))
                            .isInstanceOf(InMemoryConnectedAccountsService.class);
                });
    }
}
