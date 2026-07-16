package com.xplanet.article.outbox;

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

class ArticleChangeOutboxPublisherTest {

    private ArticleChangeOutboxMapper outboxMapper;
    private RocketMQTemplate rocketMQTemplate;
    private ArticleChangeOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(ArticleChangeOutboxMapper.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        publisher = new ArticleChangeOutboxPublisher(outboxMapper, rocketMQTemplate);
        ReflectionTestUtils.setField(publisher, "batchSize", 100);
    }

    @Test
    void shouldClaimSendAndMarkEventAsSent() {
        ArticleChangeOutboxEvent event = event(1L, "event-1", 0);
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(eq(1L), anyString(), eq(30))).thenReturn(1);
        when(outboxMapper.markSent(eq(1L), anyString())).thenReturn(1);
        when(rocketMQTemplate.syncSend(eq("xp_article_change_topic"),
                any(Message.class), eq(3000L))).thenReturn(mock(SendResult.class));

        assertThat(publisher.publishBatch()).isEqualTo(1);

        verify(outboxMapper).markSent(eq(1L), anyString());
        verify(outboxMapper, never()).releaseForRetry(eq(1L), anyString(), eq(1), anyString());
    }

    @Test
    void shouldReleaseClaimWithBackoffWhenSendFails() {
        ArticleChangeOutboxEvent event = event(2L, "event-2", 2);
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(eq(2L), anyString(), eq(30))).thenReturn(1);
        when(rocketMQTemplate.syncSend(eq("xp_article_change_topic"),
                any(Message.class), eq(3000L))).thenThrow(new IllegalStateException("broker unavailable"));

        assertThat(publisher.publishBatch()).isZero();

        verify(outboxMapper).releaseForRetry(eq(2L), anyString(), eq(4), eq("broker unavailable"));
        verify(outboxMapper, never()).markSent(eq(2L), anyString());
    }

    @Test
    void shouldSkipEventClaimedByAnotherInstance() {
        ArticleChangeOutboxEvent event = event(3L, "event-3", 0);
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(eq(3L), anyString(), eq(30))).thenReturn(0);

        assertThat(publisher.publishBatch()).isZero();

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class), eq(3000L));
    }

    private ArticleChangeOutboxEvent event(Long id, String eventId, int retryCount) {
        ArticleChangeOutboxEvent event = new ArticleChangeOutboxEvent();
        event.setId(id);
        event.setEventId(eventId);
        event.setArticleId(9L);
        event.setOperation("UPDATE");
        event.setRetryCount(retryCount);
        return event;
    }
}
