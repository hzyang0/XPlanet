package com.xplanet.article.outbox;

import com.xplanet.api.dto.ArticleChangeMessage;
import com.xplanet.common.constant.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleChangeOutboxPublisher {

    private static final int CLAIM_LEASE_SECONDS = 30;
    private static final int SEND_TIMEOUT_MS = 3000;

    private final ArticleChangeOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final String owner = UUID.randomUUID().toString();

    @Value("${cache.outbox.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${cache.outbox.publish-interval-ms:200}")
    public void publish() {
        try {
            publishBatch();
        } catch (Exception e) {
            log.error("article cache outbox batch failed", e);
        }
    }

    int publishBatch() {
        List<ArticleChangeOutboxEvent> events = outboxMapper.findPublishable(batchSize);
        int sent = 0;
        for (ArticleChangeOutboxEvent event : events) {
            if (outboxMapper.claim(event.getId(), owner, CLAIM_LEASE_SECONDS) == 0) {
                continue;
            }
            try {
                ArticleChangeMessage message = ArticleChangeMessage.builder()
                        .eventId(event.getEventId())
                        .articleId(event.getArticleId())
                        .op(event.getOperation())
                        .timestamp(System.currentTimeMillis())
                        .build();
                rocketMQTemplate.syncSend(MqTopics.TOPIC_ARTICLE_CHANGE,
                        MessageBuilder.withPayload(message).build(), SEND_TIMEOUT_MS);
                if (outboxMapper.markSent(event.getId(), owner) != 1) {
                    throw new IllegalStateException("failed to mark article cache outbox sent");
                }
                sent++;
            } catch (Exception e) {
                int delaySeconds = retryDelay(event.getRetryCount());
                outboxMapper.releaseForRetry(
                        event.getId(), owner, delaySeconds, abbreviate(e.getMessage()));
                log.warn("article cache outbox send failed, eventId={}, retryIn={}s",
                        event.getEventId(), delaySeconds);
            }
        }
        return sent;
    }

    private int retryDelay(Integer retryCount) {
        int attempt = retryCount == null ? 0 : Math.min(retryCount, 6);
        return Math.min(60, 1 << attempt);
    }

    private String abbreviate(String error) {
        if (error == null || error.isBlank()) {
            return "unknown send failure";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
