package com.example.demo.search.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class SearchPipelineConfiguration {
    @Bean
    public SimpleRabbitListenerContainerFactory contentSearchListenerFactory(
            ConnectionFactory connectionFactory,
            @Value("${app.content.events.enabled:false}") boolean eventsEnabled,
            @Value("${app.rabbit.enabled:false}") boolean rabbitEnabled,
            @Value("${app.search.consumer.enabled:true}") boolean consumerEnabled) {
        if (!eventsEnabled || !rabbitEnabled) {
            throw new IllegalArgumentException("Search requires content event publishing and RabbitMQ");
        }
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        factory.setAutoStartup(consumerEnabled);
        return factory;
    }
}
