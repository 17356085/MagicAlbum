package com.example.notification.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Listener topology for the opt-in private notification RPC. */
@Configuration
@ConditionalOnProperty(name = "app.notifications.comment-events.enabled", havingValue = "true")
public class CommentCreatedMessagingConfiguration {
    @Bean
    public DirectExchange commentCreatedDeadLetterExchange() {
        return new DirectExchange(CommentCreatedMessagingConstants.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue commentCreatedQueue() {
        return QueueBuilder.durable(CommentCreatedMessagingConstants.QUEUE)
                .withArgument("x-dead-letter-exchange", CommentCreatedMessagingConstants.DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CommentCreatedMessagingConstants.DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue commentCreatedDeadLetterQueue() {
        return QueueBuilder.durable(CommentCreatedMessagingConstants.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding commentCreatedDeadLetterBinding() {
        return BindingBuilder.bind(commentCreatedDeadLetterQueue())
                .to(commentCreatedDeadLetterExchange())
                .with(CommentCreatedMessagingConstants.DEAD_LETTER_QUEUE);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory commentCreatedListenerFactory(
            ConnectionFactory connectionFactory,
            @Value("${app.rabbit.enabled:false}") boolean rabbitEnabled
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAutoStartup(rabbitEnabled);
        return factory;
    }
}
