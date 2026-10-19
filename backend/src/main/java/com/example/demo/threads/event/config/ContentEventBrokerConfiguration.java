package com.example.demo.threads.event.config;

import com.example.demo.threads.event.service.ContentEventPublisherTransport;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable topology for the content publisher and the search consumer. */
@Configuration
@ConditionalOnProperty(name = "app.content.events.enabled", havingValue = "true")
public class ContentEventBrokerConfiguration {
    public static final String DEAD_LETTER_EXCHANGE = "content.events.dlx";
    public static final String MAIN_QUEUE = "content.search.v1";
    public static final String DEAD_LETTER_QUEUE = "content.search.failed.v1";
    public static final String DEAD_LETTER_ROUTING_KEY = "failed";
    public static final String QUARANTINE_QUEUE = "content.search.quarantine.v1";

    public ContentEventBrokerConfiguration(
            @Value("${app.rabbit.enabled:false}") boolean rabbitEnabled) {
        if (!rabbitEnabled) {
            throw new IllegalStateException("app.content.events.enabled requires app.rabbit.enabled=true");
        }
    }

    @Bean
    public DirectExchange contentEventExchange() {
        return new DirectExchange(ContentEventPublisherTransport.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange contentEventDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue contentSearchQueue() {
        return QueueBuilder.durable(MAIN_QUEUE)
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue contentSearchFailedQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE)
                .withArgument("x-queue-type", "quorum").build();
    }

    @Bean
    public Queue contentSearchQuarantineQueue() {
        return QueueBuilder.durable(QUARANTINE_QUEUE).withArgument("x-queue-type", "quorum").build();
    }

    @Bean
    public Binding contentSearchQuarantineBinding() {
        return BindingBuilder.bind(contentSearchQuarantineQueue())
                .to(contentEventDeadLetterExchange()).with("quarantined");
    }

    @Bean
    public Binding contentSearchQueueBinding() {
        return BindingBuilder.bind(contentSearchQueue())
                .to(contentEventExchange())
                .with(ContentEventPublisherTransport.ROUTING_KEY);
    }

    @Bean
    public Binding contentSearchFailedQueueBinding() {
        return BindingBuilder.bind(contentSearchFailedQueue())
                .to(contentEventDeadLetterExchange())
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}
