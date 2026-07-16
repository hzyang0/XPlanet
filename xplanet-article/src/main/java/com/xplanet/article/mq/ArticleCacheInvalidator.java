package com.xplanet.article.mq;

import com.xplanet.api.dto.ArticleChangeMessage;
import com.xplanet.article.cache.ArticleCacheManager;
import com.xplanet.common.constant.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 文章变更消息消费者，负责清除本节点 L1 和共享 Redis L2 缓存。
 *
 * <p>关键点:
 * <b>messageModel = BROADCASTING</b>。
 * 必须用广播模式,否则集群内只有一台机器消费,其他机器 L1 不会清,出现节点间不一致。
 *
 * <p>缓存删除天然幂等，因此可以安全吸收 Outbox 的重复投递；
 * 所有实例重复删除共享 L2 只增加少量 Redis 操作，但换取提交后崩溃的恢复能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.TOPIC_ARTICLE_CHANGE,
        consumerGroup = MqTopics.GROUP_ARTICLE_CACHE_CONSUMER,
        messageModel = MessageModel.BROADCASTING
)
public class ArticleCacheInvalidator implements RocketMQListener<ArticleChangeMessage> {

    private final ArticleCacheManager cacheManager;

    @Override
    public void onMessage(ArticleChangeMessage msg) {
        if (msg == null || msg.getEventId() == null || msg.getEventId().isBlank()
                || msg.getArticleId() == null || msg.getArticleId() <= 0) {
            throw new IllegalArgumentException("invalid article change event");
        }
        log.info("recv article change msg, id={}, op={}", msg.getArticleId(), msg.getOp());
        // Outbox 事件同时承担跨实例 L1 广播和崩溃恢复后的共享 L2 兜底失效。
        cacheManager.invalidate(msg.getArticleId());
    }
}
