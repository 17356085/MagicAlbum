package com.example.demo.threads.event.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pull-based Kafka reader with callback-before-commit semantics.
 *
 * <p>Each record is handed to the callback before its partition offset is
 * committed. Processing stops at the first callback or commit failure, and
 * every affected partition is rewound to its first uncommitted offset because
 * a Kafka poll advances local positions for the whole returned batch. The
 * caller owns retry, decoding, and dead-letter policy.</p>
 */
public class KafkaContentEventReader {
    // The reader is deliberately pull-based so the caller controls retry cadence.
    private final Consumer<String, byte[]> consumer;
    private final String topic;
    private final String groupId;
    private final Duration pollTimeout;
    private final Duration closeTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    public KafkaContentEventReader(Consumer<String, byte[]> consumer,
                                   String topic,
                                   String groupId,
                                   Duration pollTimeout,
                                   Duration closeTimeout) {
        if (consumer == null) {
            throw new IllegalArgumentException("Kafka consumer is required");
        }
        requireText(topic, "topic");
        requireText(groupId, "groupId");
        requireDuration(pollTimeout, "pollTimeout");
        requireDuration(closeTimeout, "closeTimeout");
        this.consumer = consumer;
        this.topic = topic;
        this.groupId = groupId;
        this.pollTimeout = pollTimeout;
        this.closeTimeout = closeTimeout;
        consumer.subscribe(List.of(topic));
    }

    /**
     * Polls once and processes records in the broker-provided partition order.
     *
     * @return number of records whose callback and offset commit both succeeded
     * @throws RuntimeException when a callback or commit fails; the failing
     * record and all later records are left uncommitted
     */
    public int pollAndProcess(java.util.function.Consumer<byte[]> processor) {
        if (processor == null) {
            throw new IllegalArgumentException("processor is required");
        }
        if (closed.get()) {
            throw new IllegalStateException("Kafka consumer is closed");
        }

        ConsumerRecords<String, byte[]> records = consumer.poll(pollTimeout);
        int processed = 0;
        Map<TopicPartition, Long> nextUncommitted = new HashMap<>();
        for (TopicPartition partition : records.partitions()) {
            nextUncommitted.put(partition, records.records(partition).get(0).offset());
        }
        try {
            for (ConsumerRecord<String, byte[]> record : records) {
                processor.accept(record.value());
                TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                long nextOffset = record.offset() + 1;
                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(nextOffset)));
                nextUncommitted.put(partition, nextOffset);
                processed++;
            }
        } catch (RuntimeException | Error failure) {
            rewindToUncommitted(records.partitions(), nextUncommitted, failure);
            throw failure;
        }
        return processed;
    }

    private void rewindToUncommitted(Set<TopicPartition> partitions,
                                     Map<TopicPartition, Long> nextUncommitted,
                                     Throwable failure) {
        for (TopicPartition partition : partitions) {
            Long offset = nextUncommitted.get(partition);
            if (offset == null) {
                continue;
            }
            try {
                consumer.seek(partition, offset);
            } catch (RuntimeException seekFailure) {
                failure.addSuppressed(seekFailure);
            }
        }
    }

    public String topic() {
        return topic;
    }

    public String groupId() {
        return groupId;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || !fitsKafkaMillis(value)) {
            throw new IllegalArgumentException(name + " must be a positive Kafka millisecond timeout");
        }
    }

    private static boolean fitsKafkaMillis(Duration value) {
        try {
            long millis = value.toMillis();
            return millis >= 1 && millis <= Integer.MAX_VALUE;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /** Close is idempotent and bounded; no background listener is started. */
    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                consumer.close(closeTimeout);
            } catch (RuntimeException ignored) {
                // Shutdown must not mask the original application failure.
            }
        }
    }
}
