package com.example.demo.notifications.service.rabbit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Private durable request queue and bounded poison destination. */
@Configuration
@ConditionalOnProperty(name = "app.notifications.comment-events.enabled", havingValue = "true")
public class CommentCreatedRabbitConfiguration {
    public static final String DEAD_LETTER_EXCHANGE = "notification.comment-created.dlx";
    public static final String DEAD_LETTER_QUEUE = "notification.comment-created.failed.v1";

    @Bean
    public DirectExchange commentCreatedDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue commentCreatedQueue() {
        return QueueBuilder.durable(CommentCreatedRabbitTransport.QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue commentCreatedDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding commentCreatedDeadLetterBinding() {
        return BindingBuilder.bind(commentCreatedDeadLetterQueue())
                .to(commentCreatedDeadLetterExchange())
                .with(DEAD_LETTER_QUEUE);
    }
}
