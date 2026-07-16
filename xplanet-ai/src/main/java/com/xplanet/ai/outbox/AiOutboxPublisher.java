package com.xplanet.ai.outbox;

import com.xplanet.api.dto.AiTaskCommand;
import com.xplanet.common.constant.MqTopics;
import com.xplanet.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Reliable relay for long-running Agent commands. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiOutboxPublisher {

    private static final int CLAIM_LEASE_SECONDS = 30;
    private static final int SEND_TIMEOUT_MS = 3000;

    private final AiOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final String owner = UUID.randomUUID().toString();

    @Value("${ai.outbox.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${ai.outbox.publish-interval-ms:500}")
    public void publish() {
        try {
            publishBatch();
        } catch (Exception e) {
            log.error("AI outbox batch failed", e);
        }
    }

    int publishBatch() {
        List<AiOutboxEvent> events = outboxMapper.findPublishable(batchSize);
        int sent = 0;
        for (AiOutboxEvent event : events) {
            if (outboxMapper.claim(event.getId(), owner, CLAIM_LEASE_SECONDS) == 0) {
                continue;
            }
            try {
                AiTaskCommand command = JsonUtil.fromJson(event.getPayload(), AiTaskCommand.class);
                String tag = "AI_TASK_CANCELLED".equals(event.getEventType())
                        ? MqTopics.TAG_AI_TASK_CANCEL : MqTopics.TAG_AI_TASK_REQUEST;
                rocketMQTemplate.syncSend(MqTopics.TOPIC_AI_TASK + ":" + tag,
                        MessageBuilder.withPayload(command).build(), SEND_TIMEOUT_MS);
                if (outboxMapper.markSent(event.getId(), owner) != 1) {
                    throw new IllegalStateException("failed to mark AI outbox sent");
                }
                sent++;
            } catch (Exception e) {
                int delaySeconds = retryDelay(event.getRetryCount());
                outboxMapper.releaseForRetry(event.getId(), owner, delaySeconds, abbreviate(e.getMessage()));
                log.warn("AI outbox send failed, eventId={}, retryIn={}s", event.getEventId(), delaySeconds);
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
