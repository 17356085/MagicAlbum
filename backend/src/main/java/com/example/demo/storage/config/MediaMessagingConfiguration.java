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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaMessagingConfiguration {
    // Object-store reconciliation must not occupy notification/search delivery threads.
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() { return scheduler("scheduled-core-"); }

    @Bean(name = "mediaPublishScheduler")
    public ThreadPoolTaskScheduler mediaPublishScheduler() { return scheduler("scheduled-media-publish-"); }

    @Bean(name = "mediaMaintenanceScheduler")
    public ThreadPoolTaskScheduler mediaMaintenanceScheduler() { return scheduler("scheduled-media-storage-"); }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

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
