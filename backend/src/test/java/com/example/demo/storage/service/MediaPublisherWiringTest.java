package com.example.demo.storage.service;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MediaPublisherWiringTest {
    @Test
    void mediaPublisherStartsWithoutOptionalContentPipelineOrJackson2Bean() {
        var factory = new CachingConnectionFactory();
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        try {
            new ApplicationContextRunner().withPropertyValues("app.media.processing-enabled=true")
                    .withBean(ConnectionFactory.class, () -> factory)
                    .withBean(MediaDeliveryTransactions.class, () -> mock(MediaDeliveryTransactions.class))
                    .withUserConfiguration(MediaRequestPublisher.class)
                    .run(context -> assertThat(context).hasSingleBean(MediaRequestPublisher.class));
        } finally { factory.destroy(); }
    }
}
