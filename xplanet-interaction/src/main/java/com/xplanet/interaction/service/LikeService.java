package com.xplanet.interaction.service;

import com.xplanet.api.dto.LikeMessage;
import com.xplanet.common.constant.CacheKeys;
import com.xplanet.common.constant.MqTopics;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 点赞服务。
 *
 * <h3>设计</h3>
 * <pre>
 * 1. 同步校验 + 写 Redis(快速响应用户)
 *    - SETNX 幂等键: 防止同一用户重复点赞
 *    - SADD userLikedSet: 已点赞集合,用于个人页查询
 *    - INCRBY likeCount: 实时计数(给前端展示用)
 *
 * 2. 异步投 MQ
 *    - 发到 RocketMQ,异步落库
 *    - 用 selectMessageQueueByHash(userId) 保证同一用户消息有序
 *    - 投递失败不阻塞用户(降级到日志补偿,生产可写本地 outbox 表)
 *
 * 3. 用户感知:
 *    用户 click → 接口在 ~5ms 返回 → 真实落库由消费者批量异步完成
 *    DB 写 QPS 大幅降低
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeService {

    private final StringRedisTemplate redis;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 点赞。返回 true=本次新增点赞成功,false=已点过(幂等,不重复计数)。
     */
    public boolean like(Long userId, Long articleId) {
        validate(userId, articleId);

        try {
            // 用"已点赞集合"做幂等判断:SADD 返回 1 表示新加入,0 表示已存在。
            // 这样幂等状态和业务状态绑定(用户确实点过),不会因为单独的幂等键过期而重复计数。
            Long added = redis.opsForSet().add(CacheKeys.userLikedSet(userId), String.valueOf(articleId));
            if (added == null || added == 0) {
                // 已经点过赞,幂等返回,不重复计数、不重复发消息
                return false;
            }

            // 确实是新点赞,实时计数 +1
            redis.opsForValue().increment(CacheKeys.articleLikeCount(articleId));

            // 异步投 MQ 落库
            sendLikeMq(userId, articleId, 1);
            return true;
        } catch (Exception e) {
            // 降级:Redis 异常时回滚可能已写入的集合,避免计数与集合不一致
            try { redis.opsForSet().remove(CacheKeys.userLikedSet(userId), String.valueOf(articleId)); }
            catch (Exception ignore) {}
            log.warn("点赞降级,userId={}, articleId={}, err={}", userId, articleId, e.getMessage());
            throw new BizException(ErrorCode.SERVICE_BUSY);
        }
    }

    /**
     * 取消点赞。返回 true=取消成功或本就未点赞(幂等)。
     */
    public boolean cancel(Long userId, Long articleId) {
        validate(userId, articleId);

        try {
            // SREM 返回移除数量:1=确实取消了,0=本来就没点过(幂等)
            Long removed = redis.opsForSet().remove(CacheKeys.userLikedSet(userId), String.valueOf(articleId));
            if (removed != null && removed > 0) {
                redis.opsForValue().decrement(CacheKeys.articleLikeCount(articleId));
                sendLikeMq(userId, articleId, -1);
            }
            return true;
        } catch (Exception e) {
            log.warn("取消点赞降级,userId={}, articleId={}, err={}", userId, articleId, e.getMessage());
            throw new BizException(ErrorCode.SERVICE_BUSY);
        }
    }

    private void validate(Long userId, Long articleId) {
        if (userId == null || articleId == null || userId <= 0 || articleId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    /**
     * 发 MQ。
     * <p>用 sync send + 按 userId hash 选 queue,保证同一 user 顺序。
     * 失败时只记日志(简化);生产应写本地 outbox 表 + 定时补偿任务。
     */
    private void sendLikeMq(Long userId, Long articleId, int delta) {
        LikeMessage msg = LikeMessage.builder()
                .actionId(UUID.randomUUID().toString())
                .userId(userId)
                .articleId(articleId)
                .delta(delta)
                .timestamp(System.currentTimeMillis())
                .build();

        String tag = delta > 0 ? MqTopics.TAG_LIKE_ADD : MqTopics.TAG_LIKE_CANCEL;
        String destination = MqTopics.TOPIC_LIKE + ":" + tag;

        // hashKey 用 userId,保证同一用户的多条消息进入同一队列 → 顺序消费
        rocketMQTemplate.asyncSendOrderly(destination,
                MessageBuilder.withPayload(msg).build(),
                String.valueOf(userId),
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.debug("like mq sent, actionId={}", msg.getActionId());
                    }
                    @Override
                    public void onException(Throwable e) {
                        // TODO: 写 outbox 表
                        log.error("like mq failed, actionId={}", msg.getActionId(), e);
                    }
                },
                3000L);
    }
}
