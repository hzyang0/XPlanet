package com.xplanet.interaction.service;

import com.xplanet.interaction.persistence.LikeOutboxEvent;
import com.xplanet.interaction.persistence.LikeOutboxMapper;
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

class LikeOutboxPublisherTest {

    private LikeOutboxMapper outboxMapper;
    private RocketMQTemplate rocketMQTemplate;
    private LikeOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(LikeOutboxMapper.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        publisher = new LikeOutboxPublisher(outboxMapper, rocketMQTemplate);
        ReflectionTestUtils.setField(publisher, "batchSize", 100);
    }

    @Test
    void shouldClaimSendAndMarkEventAsSent() {
        LikeOutboxEvent event = event(1L, "event-1", 1, 0);
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(eq(1L), anyString(), eq(30))).thenReturn(1);
        when(rocketMQTemplate.syncSend(eq("xp_like_topic:ADD"), any(Message.class), eq(3000L)))
                .thenReturn(mock(SendResult.class));

        assertThat(publisher.publishBatch()).isEqualTo(1);

        verify(outboxMapper).markSent(eq(1L), anyString());
        verify(outboxMapper, never()).releaseForRetry(eq(1L), anyString(), eq(1), anyString());
    }

    @Test
    void shouldReleaseClaimWithBackoffWhenSendFails() {
        LikeOutboxEvent event = event(2L, "event-2", -1, 2);
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(eq(2L), anyString(), eq(30))).thenReturn(1);
        when(rocketMQTemplate.syncSend(eq("xp_like_topic:CANCEL"), any(Message.class), eq(3000L)))
                .thenThrow(new IllegalStateException("broker unavailable"));

        assertThat(publisher.publishBatch()).isZero();

        verify(outboxMapper).releaseForRetry(eq(2L), anyString(), eq(4), eq("broker unavailable"));
        verify(outboxMapper, never()).markSent(eq(2L), anyString());
    }

    @Test
    void shouldSkipEventClaimedByAnotherInstance() {
        LikeOutboxEvent event = event(3L, "event-3", 1, 0);
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(eq(3L), anyString(), eq(30))).thenReturn(0);

        assertThat(publisher.publishBatch()).isZero();

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class), eq(3000L));
    }

    private LikeOutboxEvent event(Long id, String eventId, int delta, int retryCount) {
        LikeOutboxEvent event = new LikeOutboxEvent();
        event.setId(id);
        event.setEventId(eventId);
        event.setUserId(7L);
        event.setArticleId(9L);
        event.setDelta(delta);
        event.setRetryCount(retryCount);
        return event;
    }
}
