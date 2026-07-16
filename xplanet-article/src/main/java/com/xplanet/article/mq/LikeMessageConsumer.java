package com.xplanet.article.mq;

import com.xplanet.api.dto.LikeMessage;
import com.xplanet.article.projection.LikeCountDeltaMapper;
import com.xplanet.common.constant.MqTopics;
import com.xplanet.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 把点赞状态事件持久化为待投影增量。
 *
 * <p>event_id 唯一约束是最终幂等保证。消息可以并发、重复或乱序到达，因为生产端只在
 * 状态真实变化时产生 delta，而整数加法可交换；消费端不再依赖短 TTL 的 Redis SETNX。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.TOPIC_LIKE,
        consumerGroup = MqTopics.GROUP_LIKE_CONSUMER,
        consumeMode = ConsumeMode.CONCURRENTLY,
        consumeThreadMax = 20
)
public class LikeMessageConsumer implements RocketMQListener<MessageExt> {

    private final LikeCountDeltaMapper deltaMapper;

    @Override
    @Transactional
    public void onMessage(MessageExt message) {
        LikeMessage event = JsonUtil.fromJson(
                new String(message.getBody(), StandardCharsets.UTF_8), LikeMessage.class);
        validate(event);

        int inserted = deltaMapper.insertIgnore(
                event.getActionId(), event.getArticleId(), event.getDelta());
        if (inserted == 0) {
            log.debug("duplicate like event ignored, eventId={}", event.getActionId());
        }
    }

    private void validate(LikeMessage event) {
        if (event == null || event.getActionId() == null || event.getActionId().isBlank()
                || event.getArticleId() == null || event.getArticleId() <= 0
                || event.getUserId() == null || event.getUserId() <= 0
                || (event.getDelta() != 1 && event.getDelta() != -1)) {
            throw new IllegalArgumentException("invalid like event");
        }
    }
}
