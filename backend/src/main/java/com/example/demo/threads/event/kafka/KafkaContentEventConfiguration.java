package com.example.demo.threads.event.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/** Opt-in native Kafka clients for content events; no client is built by default. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"app.content.events.enabled", "app.content.events.kafka.enabled"},
        havingValue = "true")
@EnableConfigurationProperties(KafkaContentEventProperties.class)
public class KafkaContentEventConfiguration {
    @Bean("kafkaContentEventPublisher")
    public KafkaContentEventPublisher kafkaContentEventPublisher(
            ObjectMapper objectMapper,
            KafkaContentEventProperties properties) {
        properties.validate();
        return new KafkaContentEventPublisher(
                new KafkaProducer<>(producerProperties(properties)),
                objectMapper,
                properties.getTopic(),
                properties.getSendTimeout(),
                properties.getCloseTimeout());
    }

    @Bean("kafkaContentEventReader")
    public KafkaContentEventReader kafkaContentEventReader(KafkaContentEventProperties properties) {
        properties.validate();
        return new KafkaContentEventReader(
                new KafkaConsumer<>(consumerProperties(properties)),
                properties.getTopic(),
                properties.getGroupId(),
                properties.getPollTimeout(),
                properties.getCloseTimeout());
    }

    static Properties producerProperties(KafkaContentEventProperties properties) {
        Properties values = new Properties();
        values.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        values.put(ProducerConfig.CLIENT_ID_CONFIG, properties.getClientId() + "-publisher");
        values.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        values.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        values.put(ProducerConfig.ACKS_CONFIG, "all");
        values.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        int sendTimeout = properties.getSendTimeout().toMillis() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) properties.getSendTimeout().toMillis();
        int maxBlockTimeout = properties.getMaxBlockTimeout().toMillis() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) properties.getMaxBlockTimeout().toMillis();
        values.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        values.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, sendTimeout);
        values.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, sendTimeout);
        values.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockTimeout);
        return values;
    }

    static Properties consumerProperties(KafkaContentEventProperties properties) {
        Properties values = new Properties();
        values.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        values.put(ConsumerConfig.CLIENT_ID_CONFIG, properties.getClientId() + "-reader");
        values.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getGroupId());
        values.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        values.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        values.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        values.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        values.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return values;
    }
}
