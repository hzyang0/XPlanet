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
 * 文章变更消息消费者,负责清除本节点的 L1 本地缓存。
 *
 * <p>关键点:
 * <b>messageModel = BROADCASTING</b>。
 * 必须用广播模式,否则集群内只有一台机器消费,其他机器 L1 不会清,出现节点间不一致。
 *
 * <p>本节点本身在 ArticleServiceImpl 已经清过了,重复清一次无所谓(幂等)。
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
        log.info("recv article change msg, id={}, op={}", msg.getArticleId(), msg.getOp());
        // 只清 L1; L2 在生产端已经清过,这里再清反而浪费 redis ops
        cacheManager.invalidateLocal(msg.getArticleId());
    }
}
