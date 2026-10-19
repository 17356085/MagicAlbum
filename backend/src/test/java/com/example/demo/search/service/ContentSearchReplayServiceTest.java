package com.example.demo.search.service;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.event.service.ContentEventPublisherTransport;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentSearchReplayServiceTest {
    private static final String FAILED_QUEUE = "content.search.failed.v1";

    private final ConnectionFactory connections = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);
    private final AMQP.Queue.DeclareOk declaration = mock(AMQP.Queue.DeclareOk.class);
    private final ContentEventPublisherTransport publisher = mock(ContentEventPublisherTransport.class);
    private final ContentSearchConsumer consumer = mock(ContentSearchConsumer.class);
    private final ContentSearchReplayService service =
            new ContentSearchReplayService(connections, publisher, consumer);

    @BeforeEach
    void setUp() throws Exception {
        when(connections.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        when(channel.queueDeclarePassive(FAILED_QUEUE)).thenReturn(declaration);
    }

    @Test
    void quarantines_poison_then_continues_with_a_following_valid_delivery() throws Exception {
        byte[] poison = "{\"requestId\":\"sensitive-original\",\"schemaVersion\":9}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] validBody = "valid-envelope".getBytes(StandardCharsets.UTF_8);
        ContentEventMessage valid = validEvent();
        when(channel.basicGet(FAILED_QUEUE, false))
                .thenReturn(delivery(11, poison), delivery(12, validBody), null);
        decodePoisonAndValid(poison, validBody, valid);
        when(declaration.getMessageCount()).thenReturn(0);

        ContentSearchReplayService.ReplayResult result = service.replay(2);

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.quarantined()).isEqualTo(1);
        assertThat(result.remaining()).isZero();
        assertThat(result.failureCode()).isNull();
        var order = inOrder(publisher, channel);
        order.verify(publisher).quarantine(poison);
        order.verify(channel).basicAck(11, false);
        order.verify(publisher).publish(valid);
        order.verify(channel).basicAck(12, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void quarantine_failure_requeues_the_original_delivery_and_stops_replay() throws Exception {
        byte[] poison = "{\"requestId\":\"sensitive-original\",\"schemaVersion\":9}"
                .getBytes(StandardCharsets.UTF_8);
        when(channel.basicGet(FAILED_QUEUE, false)).thenReturn(delivery(21, poison));
        doAnswer(invocation -> {
            throw new InvalidContentEventException("UNSUPPORTED_ENVELOPE");
        }).when(consumer).decode(any(byte[].class));
        doThrow(new ContentEventPublisherTransport.PublishException(
                ContentEventPublisherTransport.Code.CONFIRM_TIMEOUT))
                .when(publisher).quarantine(poison);
        when(declaration.getMessageCount()).thenReturn(1);

        ContentSearchReplayService.ReplayResult result = service.replay(1);

        assertThat(result.published()).isZero();
        assertThat(result.quarantined()).isZero();
        assertThat(result.remaining()).isEqualTo(1);
        assertThat(result.failureCode()).isEqualTo("QUARANTINE_UNAVAILABLE");
        verify(channel).basicNack(21, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(publisher).quarantine(poison);
    }

    private void decodePoisonAndValid(byte[] poison, byte[] validBody, ContentEventMessage valid) {
        doAnswer(invocation -> {
            byte[] body = invocation.getArgument(0);
            if (Arrays.equals(body, poison)) {
                throw new InvalidContentEventException("UNSUPPORTED_ENVELOPE");
            }
            assertThat(body).isEqualTo(validBody);
            return valid;
        }).when(consumer).decode(any(byte[].class));
    }

    private ContentEventMessage validEvent() {
        return new ContentEventMessage(UUID.randomUUID().toString(), 1, "THREAD", 7,
                2, "THREAD_UPSERTED", Instant.parse("2026-10-19T00:00:00Z"), null);
    }

    private GetResponse delivery(long tag, byte[] body) {
        return new GetResponse(new Envelope(tag, false, "content.events.dlx", "failed"),
                null, body, 0);
    }
}
