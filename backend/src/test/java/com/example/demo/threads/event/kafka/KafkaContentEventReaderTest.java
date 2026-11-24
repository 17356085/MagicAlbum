package com.example.demo.threads.event.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaContentEventReaderTest {
    private final Consumer<String, byte[]> consumer = mock(Consumer.class);
    private final TopicPartition partition = new TopicPartition("content.events.v1", 0);

    @Test
    void commits_each_offset_only_after_callback_succeeds() {
        when(consumer.poll(any(Duration.class))).thenReturn(records(
                new ConsumerRecord<>(partition.topic(), partition.partition(), 10L, "THREAD:7", bytes("one")),
                new ConsumerRecord<>(partition.topic(), partition.partition(), 11L, "THREAD:7", bytes("two"))));
        KafkaContentEventReader reader = reader();
        AtomicInteger callbacks = new AtomicInteger();

        int processed = reader.pollAndProcess(payload -> callbacks.incrementAndGet());

        assertThat(processed).isEqualTo(2);
        assertThat(callbacks).hasValue(2);
        verify(consumer).commitSync(Map.of(partition, new OffsetAndMetadata(11L)));
        verify(consumer).commitSync(Map.of(partition, new OffsetAndMetadata(12L)));
    }

    @Test
    void callback_failure_rewinds_first_uncommitted_offset_and_does_not_commit_it() {
        when(consumer.poll(any(Duration.class))).thenReturn(records(
                new ConsumerRecord<>(partition.topic(), partition.partition(), 10L, "THREAD:7", bytes("one")),
                new ConsumerRecord<>(partition.topic(), partition.partition(), 11L, "THREAD:7", bytes("two"))));
        KafkaContentEventReader reader = reader();
        AtomicInteger callbacks = new AtomicInteger();

        assertThatThrownBy(() -> reader.pollAndProcess(payload -> {
            if (callbacks.incrementAndGet() == 2) {
                throw new IllegalStateException("decoder failed");
            }
        })).isInstanceOf(IllegalStateException.class);

        verify(consumer).seek(eq(partition), eq(11L));
        verify(consumer, times(0)).commitSync(Map.of(partition, new OffsetAndMetadata(12L)));
    }

    @Test
    void commit_failure_also_rewinds_without_advancing_durable_position() {
        when(consumer.poll(any(Duration.class))).thenReturn(records(
                new ConsumerRecord<>(partition.topic(), partition.partition(), 10L, "THREAD:7", bytes("one"))));
        KafkaContentEventReader reader = reader();
        doAnswer(invocation -> {
            throw new IllegalStateException("commit failed");
        }).when(consumer).commitSync(any(Map.class));

        assertThatThrownBy(() -> reader.pollAndProcess(payload -> { }))
                .isInstanceOf(IllegalStateException.class);

        verify(consumer).seek(eq(partition), eq(10L));
    }

    @Test
    void close_is_bounded_and_idempotent() {
        KafkaContentEventReader reader = reader();

        reader.close();
        reader.close();

        verify(consumer).close(Duration.ofSeconds(1));
    }

    private KafkaContentEventReader reader() {
        return new KafkaContentEventReader(
                consumer, partition.topic(), "content-search-v1", Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    private ConsumerRecords<String, byte[]> records(ConsumerRecord<String, byte[]>... values) {
        return new ConsumerRecords<>(Map.of(partition, List.of(values)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
