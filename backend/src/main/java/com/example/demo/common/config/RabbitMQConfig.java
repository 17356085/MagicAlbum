package com.example.demo.common.config;

import com.example.demo.chat.service.ChatChangePublisher;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
public class RabbitMQConfig {

    @Bean
    public FanoutExchange chatRealtimeExchange() {
        return new FanoutExchange(ChatChangePublisher.EXCHANGE);
    }

    @Bean
    public Queue threadSummaryQueue() {
        return new Queue(com.example.demo.ai.summary.service.SummaryTaskPublisher.QUEUE, true);
    }
}
