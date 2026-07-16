package com.xplanet.interaction.service;

import com.xplanet.api.dto.LikeMessage;
import com.xplanet.common.constant.MqTopics;
import com.xplanet.interaction.persistence.LikeOutboxEvent;
import com.xplanet.interaction.persistence.LikeOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 可恢复的 Outbox relay。发送成功后再标记；崩溃造成的重复投递由消费端唯一 eventId 吸收。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeOutboxPublisher {

    private static final int CLAIM_LEASE_SECONDS = 30;
    private static final int SEND_TIMEOUT_MS = 3000;

    private final LikeOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final String owner = UUID.randomUUID().toString();

    @Value("${like.outbox.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${like.outbox.publish-interval-ms:500}")
    public void publish() {
        try {
            publishBatch();
        } catch (Exception e) {
            log.error("like outbox batch failed", e);
        }
    }

    int publishBatch() {
        List<LikeOutboxEvent> events = outboxMapper.findPublishable(batchSize);
        int sent = 0;
        for (LikeOutboxEvent event : events) {
            if (outboxMapper.claim(event.getId(), owner, CLAIM_LEASE_SECONDS) == 0) {
                continue;
            }
            try {
                LikeMessage message = LikeMessage.builder()
                        .actionId(event.getEventId())
                        .userId(event.getUserId())
                        .articleId(event.getArticleId())
                        .delta(event.getDelta())
                        .timestamp(System.currentTimeMillis())
                        .build();
                String tag = event.getDelta() > 0 ? MqTopics.TAG_LIKE_ADD : MqTopics.TAG_LIKE_CANCEL;
                rocketMQTemplate.syncSend(MqTopics.TOPIC_LIKE + ":" + tag,
                        MessageBuilder.withPayload(message).build(), SEND_TIMEOUT_MS);
                outboxMapper.markSent(event.getId(), owner);
                sent++;
            } catch (Exception e) {
                int delaySeconds = retryDelay(event.getRetryCount());
                outboxMapper.releaseForRetry(event.getId(), owner, delaySeconds, abbreviate(e.getMessage()));
                log.warn("like outbox send failed, eventId={}, retryIn={}s", event.getEventId(), delaySeconds);
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
