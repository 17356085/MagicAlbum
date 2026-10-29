package com.example.demo.storage.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaMessagingConfiguration {
    @Bean
    public Declarables mediaTopology() {
        DirectExchange main = new DirectExchange("media.jobs.v1", true, false);
        DirectExchange retry = new DirectExchange("media.jobs.retry.v1", true, false);
        DirectExchange quarantine = new DirectExchange("media.jobs.dlx.v1", true, false);
        Queue processQueue = QueueBuilder.durable("media.process.v1").build();
        Queue retryQueue = QueueBuilder.durable("media.retry.v1").ttl(10000)
                .deadLetterExchange("media.jobs.v1").deadLetterRoutingKey("process").build();
        Queue quarantineQueue = QueueBuilder.durable("media.quarantine.v1").build();
        Binding processBinding = BindingBuilder.bind(processQueue).to(main).with("process");
        Binding retryBinding = BindingBuilder.bind(retryQueue).to(retry).with("retry");
        Binding quarantineBinding = BindingBuilder.bind(quarantineQueue).to(quarantine).with("quarantined");
        return new Declarables(main, retry, quarantine, processQueue, retryQueue, quarantineQueue,
                processBinding, retryBinding, quarantineBinding);
    }
}
