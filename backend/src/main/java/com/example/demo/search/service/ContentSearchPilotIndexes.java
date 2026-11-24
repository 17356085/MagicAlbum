package com.example.demo.search.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"app.search.enabled", "app.content.events.enabled", "app.content.events.kafka.enabled"},
        havingValue = "true")
public record ContentSearchPilotIndexes(String rabbit, String kafka) {
    public ContentSearchPilotIndexes(
            @Value("${app.search.kafka-pilot.rabbit-index}") String rabbit,
            @Value("${app.search.kafka-pilot.kafka-index}") String kafka) {
        SearchRebuildService.requireManagedPhysicalIndex(rabbit);
        SearchRebuildService.requireManagedPhysicalIndex(kafka);
        if (rabbit.equals(kafka)) {
            throw new IllegalArgumentException("The RabbitMQ and Kafka pilot indexes must be distinct");
        }
        this.rabbit = rabbit;
        this.kafka = kafka;
    }

    public String target(Carrier carrier) {
        return carrier == Carrier.RABBITMQ ? rabbit : kafka;
    }

    public String peer(Carrier carrier) {
        return carrier == Carrier.RABBITMQ ? kafka : rabbit;
    }

    public enum Carrier { RABBITMQ, KAFKA }
}
