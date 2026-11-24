package com.example.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HomeBffProperties.class)
class HomeOverviewConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "home.bff", name = "enabled", havingValue = "true")
    WebClient.Builder homeOverviewWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @LoadBalanced
    @ConditionalOnProperty(prefix = "home.bff", name = "enabled", havingValue = "true")
    WebClient.Builder homeOverviewLoadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.bff", name = "enabled", havingValue = "true")
    HomeOverviewClient homeOverviewClient(
            @Qualifier("homeOverviewWebClientBuilder") WebClient.Builder directBuilder,
            @Qualifier("homeOverviewLoadBalancedWebClientBuilder") WebClient.Builder loadBalancedBuilder,
            HomeBffProperties properties
    ) {
        return new HomeOverviewHttpClient(directBuilder, loadBalancedBuilder, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "home.bff", name = "enabled", havingValue = "true")
    HomeOverviewService homeOverviewService(HomeOverviewClient client, HomeBffProperties properties) {
        return new HomeOverviewService(client, properties);
    }
}
