package com.example.demo.user.connected.service;

import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import com.example.demo.user.connected.service.mock.InMemoryConnectedAccountsService;
import com.example.demo.user.connected.service.persistent.PersistentConnectedAccountsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConnectedAccountsConfiguration {
    @Bean
    ConnectedAccountsService connectedAccountsService(ObjectProvider<UserConnectedAccountRepository> repositories) {
        UserConnectedAccountRepository repository = repositories.getIfAvailable();
        return repository == null
                ? new InMemoryConnectedAccountsService()
                : new PersistentConnectedAccountsService(repository);
    }
}
