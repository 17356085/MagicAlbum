package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ContentSearchConsumerTest {
    private final ThreadSearchProjectionService owner = mock(ThreadSearchProjectionService.class);
    private final ContentSearchIndex index = mock(ContentSearchIndex.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ContentSearchConsumer consumer = new ContentSearchConsumer(owner, index, mapper, 3, 0);
    private final Channel channel = mock(Channel.class);

    @Test
    void acknowledgements_follow_persistence_and_newer_owner_keeps_its_provenance() throws Exception {
        ContentEventMessage old = event(1, UUID.randomUUID().toString());
        ThreadSearchDocument current = document(2, UUID.randomUUID().toString());
        when(owner.read(7)).thenReturn(Optional.of(current));
        consumer.onMessage(message(old), channel);
        var order = inOrder(index, channel);
        order.verify(index).put(ContentSearchIndex.ALIAS, current);
        order.verify(channel).basicAck(19, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void unknown_schema_is_quarantined_without_reading_or_indexing_content() throws Exception {
        ContentEventMessage unsupported = new ContentEventMessage(UUID.randomUUID().toString(), 9,
                "THREAD", 7, 1, "THREAD_UPSERTED", Instant.now(), null);
        consumer.onMessage(message(unsupported), channel);
        verify(channel).basicNack(19, false, false);
        verifyNoInteractions(owner, index);
    }

    @Test
    void same_version_conflicting_event_is_quarantined() throws Exception {
        when(owner.read(7)).thenReturn(Optional.of(document(1, UUID.randomUUID().toString())));
        consumer.onMessage(message(event(1, UUID.randomUUID().toString())), channel);
        verify(channel).basicNack(19, false, false);
        verifyNoInteractions(index);
    }

    @Test
    void dependency_failures_have_bounded_retries_and_remain_in_durable_failed_queue() throws Exception {
        ContentEventMessage event = event(1, UUID.randomUUID().toString());
        ThreadSearchDocument document = document(1, event.eventId());
        when(owner.read(7)).thenReturn(Optional.of(document));
        doThrow(new IllegalStateException("offline")).when(index).put(ContentSearchIndex.ALIAS, document);
        consumer.onMessage(message(event), channel);
        verify(index, times(3)).put(ContentSearchIndex.ALIAS, document);
        verify(channel).basicNack(19, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private Message message(ContentEventMessage event) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(19);
        return new Message(mapper.writeValueAsBytes(event), properties);
    }

    private ContentEventMessage event(long version, String eventId) {
        return new ContentEventMessage(eventId, 1, "THREAD", 7, version, "THREAD_UPSERTED", Instant.now(), null);
    }

    private ThreadSearchDocument document(long version, String eventId) {
        return new ThreadSearchDocument(7, version, true, "title", "body", List.of(), 1L,
                Instant.parse("2026-10-19T00:00:00Z"), eventId, "THREAD_UPSERTED");
    }
}
