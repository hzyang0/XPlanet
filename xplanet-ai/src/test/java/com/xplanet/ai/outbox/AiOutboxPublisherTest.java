package com.xplanet.ai.outbox;

import com.xplanet.api.dto.AiTaskCommand;
import com.xplanet.common.util.JsonUtil;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiOutboxPublisherTest {

    private AiOutboxMapper mapper;
    private RocketMQTemplate rocketMQTemplate;
    private AiOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        mapper = mock(AiOutboxMapper.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        publisher = new AiOutboxPublisher(mapper, rocketMQTemplate);
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
    }

    @Test
    void shouldSendRequestAndMarkSent() {
        AiOutboxEvent event = event(1L, "event-1", "AI_TASK_REQUESTED", 0);
        when(mapper.findPublishable(50)).thenReturn(List.of(event));
        when(mapper.claim(eq(1L), anyString(), eq(30))).thenReturn(1);
        when(mapper.markSent(eq(1L), anyString())).thenReturn(1);
        when(rocketMQTemplate.syncSend(eq("xp_ai_task_topic:REQUEST"), any(Message.class), eq(3000L)))
                .thenReturn(mock(SendResult.class));

        assertThat(publisher.publishBatch()).isEqualTo(1);
        verify(mapper).markSent(eq(1L), anyString());
    }

    @Test
    void shouldUseCancelTag() {
        AiOutboxEvent event = event(2L, "event-2", "AI_TASK_CANCELLED", 0);
        when(mapper.findPublishable(50)).thenReturn(List.of(event));
        when(mapper.claim(eq(2L), anyString(), eq(30))).thenReturn(1);
        when(mapper.markSent(eq(2L), anyString())).thenReturn(1);

        assertThat(publisher.publishBatch()).isEqualTo(1);
        verify(rocketMQTemplate).syncSend(eq("xp_ai_task_topic:CANCEL"), any(Message.class), eq(3000L));
    }

    @Test
    void shouldReleaseWithBackoffWhenBrokerFails() {
        AiOutboxEvent event = event(3L, "event-3", "AI_TASK_REQUESTED", 3);
        when(mapper.findPublishable(50)).thenReturn(List.of(event));
        when(mapper.claim(eq(3L), anyString(), eq(30))).thenReturn(1);
        when(rocketMQTemplate.syncSend(eq("xp_ai_task_topic:REQUEST"), any(Message.class), eq(3000L)))
                .thenThrow(new IllegalStateException("broker unavailable"));

        assertThat(publisher.publishBatch()).isZero();
        verify(mapper).releaseForRetry(eq(3L), anyString(), eq(8), eq("broker unavailable"));
        verify(mapper, never()).markSent(eq(3L), anyString());
    }

    private AiOutboxEvent event(Long id, String eventId, String type, int retryCount) {
        AiTaskCommand command = AiTaskCommand.builder()
                .eventId(eventId).eventType(type).schemaVersion(1)
                .taskId(9L).runId("run-1").aggregateVersion(0).build();
        AiOutboxEvent event = new AiOutboxEvent();
        event.setId(id);
        event.setEventId(eventId);
        event.setEventType(type);
        event.setPayload(JsonUtil.toJson(command));
        event.setRetryCount(retryCount);
        return event;
    }
}
