package com.example.demo.common.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_THREAD_SUMMARY = "thread.summary.queue";

    @Bean
    public Queue threadSummaryQueue() {
        return new Queue(QUEUE_THREAD_SUMMARY, true);
    }
}
