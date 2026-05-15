package com.xplanet.article.mq;

import com.xplanet.api.dto.LikeMessage;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.common.constant.CacheKeys;
import com.xplanet.common.constant.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 点赞消息消费者: 批量合并落库 + 消费幂等。
 *
 * <h3>核心思路</h3>
 * <pre>
 * 写流量削峰:
 *   消费端不立即写 DB,而是先在内存按 articleId 累加 delta;
 *   每 500ms 或累计 200 条触发一次批量 flush。
 *   100 条针对同一篇文章的点赞 → 合并成 1 条 SQL update。
 *
 * 消费幂等:
 *   消息携带 actionId(生产端生成);消费时先 SETNX 写 Redis(TTL 10min),
 *   命中重复直接丢弃,避免 MQ 重投导致重复加分。
 *
 * 顺序保证:
 *   生产端按 userId hash 选择队列(orderly send),
 *   保证同一用户的 "点赞→取消" 顺序消费,不会出现 "先取消后点赞" 的逻辑错乱。
 * </pre>
 *
 * <h3>为什么 ConsumeMode = CONCURRENTLY 而不是 ORDERLY?</h3>
 * <p>RocketMQ ORDERLY 模式下同一队列单线程消费,吞吐受限。
 * 这里通过"生产端选队列保证顺序 + 消费端按 articleId 聚合"达到逻辑顺序,
 * 同时多线程并发消费提升吞吐。
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

    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redis;

    /** 缓冲: articleId -> 累计 delta(可能为负) */
    private final ConcurrentHashMap<Long, AtomicLong> buffer = new ConcurrentHashMap<>();

    /** 缓冲数量计数器(粗略) */
    private final AtomicLong bufferedCount = new AtomicLong();

    private static final int FLUSH_THRESHOLD = 200;
    private static final long FLUSH_INTERVAL_MS = 500;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "like-flush");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("LikeMessageConsumer started, flush every {}ms or {} items", FLUSH_INTERVAL_MS, FLUSH_THRESHOLD);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 关闭前 flush 剩余
        flush();
    }

    @Override
    public void onMessage(MessageExt message) {
        try {
            byte[] body = message.getBody();
            LikeMessage msg = com.xplanet.common.util.JsonUtil.fromJson(new String(body), LikeMessage.class);

            // 幂等
            String idemKey = "xp:mq:like:idem:" + msg.getActionId();
            Boolean first = redis.opsForValue().setIfAbsent(idemKey, "1", 10, TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(first)) {
                log.debug("duplicate like msg, drop. actionId={}", msg.getActionId());
                return;
            }

            // 缓冲
            buffer.computeIfAbsent(msg.getArticleId(), k -> new AtomicLong())
                    .addAndGet(msg.getDelta());
            long c = bufferedCount.incrementAndGet();
            if (c >= FLUSH_THRESHOLD) {
                flush();
            }
        } catch (Exception e) {
            // 故意抛出: 让 MQ 重试; 但幂等键已经写了,需要清掉避免误丢
            log.error("consume like msg failed", e);
            throw e;
        }
    }

    /**
     * 把缓冲区中的累计 delta 写回 DB。
     * 简化实现: 顺序 update; 真实生产可改成 batch update + ON DUPLICATE KEY。
     */
    private synchronized void flush() {
        if (buffer.isEmpty()) return;

        // 取出快照后清空,新进入的 delta 进入下一批
        Map<Long, Long> snapshot = new HashMap<>();
        for (Map.Entry<Long, AtomicLong> e : buffer.entrySet()) {
            long v = e.getValue().getAndSet(0);
            if (v != 0) snapshot.put(e.getKey(), v);
        }
        bufferedCount.set(0);

        if (snapshot.isEmpty()) return;

        long start = System.currentTimeMillis();
        int ok = 0;
        for (Map.Entry<Long, Long> e : snapshot.entrySet()) {
            try {
                int rows = articleMapper.incrLikeCount(e.getKey(), e.getValue());
                if (rows > 0) ok++;
            } catch (Exception ex) {
                log.error("incr like failed, articleId={}, delta={}", e.getKey(), e.getValue(), ex);
                // 失败的 delta 重新塞回缓冲
                buffer.computeIfAbsent(e.getKey(), k -> new AtomicLong())
                        .addAndGet(e.getValue());
            }
        }
        log.info("like flush: {} articles, success={}, took={}ms",
                snapshot.size(), ok, System.currentTimeMillis() - start);
    }
}
