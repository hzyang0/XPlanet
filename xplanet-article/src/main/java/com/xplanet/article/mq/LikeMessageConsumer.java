package com.xplanet.article.mq;

import com.xplanet.api.dto.LikeMessage;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.article.like.ArticleLikeMapper;
import com.xplanet.common.constant.MqTopics;
import com.xplanet.common.util.JsonUtil;
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
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 点赞消息消费者:Redis 共享缓冲 + 批量合并落库 + 消费幂等。
 *
 * <h3>为什么用 Redis Hash 做缓冲,而不是进程内内存?</h3>
 * <p>早期版本用进程内 ConcurrentHashMap 累加 delta,有个致命缺陷:
 * 实例崩溃时,内存里尚未 flush 的增量直接丢失。
 * 改用 <b>Redis Hash 共享缓冲</b>(key 固定,field=articleId,value=累计delta,用 HINCRBY 原子累加):
 * <ul>
 *   <li>缓冲数据落在 Redis,单个应用实例崩溃不丢数据,重启后或其他实例继续 flush</li>
 *   <li>HINCRBY 天然原子,多实例并发累加同一文章不会丢更新</li>
 *   <li>flush 时用 HGETALL 取出全部并删除,合并成批量 UPDATE</li>
 * </ul>
 *
 * <h3>为什么用 Hash 而不是 Redis Stream?</h3>
 * <p>Stream 适合"需要保留每条消息、支持消费组回溯"的场景。
 * 而点赞这里只需要"按文章把 delta 累加合并",不关心每一条的明细,
 * Hash 的 HINCRBY 正好是"累加"语义,更贴切、更省空间。
 * 选型要看数据结构语义是否匹配业务,不是越复杂越好。
 *
 * <h3>幂等</h3>
 * <p>消息带 actionId,SETNX 去重防 MQ 重投。失败时删幂等键,让重投不被误判。
 *
 * <h3>顺序</h3>
 * <p>生产端按 userId 选队列(orderly send),保证同一用户"点赞→取消"有序。
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
    private final ArticleLikeMapper articleLikeMapper;
    private final StringRedisTemplate redis;

    /** Redis 共享缓冲:Hash,field=articleId,value=累计 delta */
    private static final String BUFFER_KEY = "xp:like:buffer";
    private static final long FLUSH_INTERVAL_MS = 500;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "like-flush");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flushSafe, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("LikeMessageConsumer started, flush every {}ms (Redis Hash buffer)", FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        flushSafe(); // 关闭前 flush 剩余
    }

    @Override
    public void onMessage(MessageExt message) {
        String idemKey = null;
        try {
            byte[] body = message.getBody();
            LikeMessage msg = JsonUtil.fromJson(new String(body), LikeMessage.class);
            int newStatus = msg.getDelta() > 0 ? 1 : 0;  // delta>0 点赞, <0 取消

            // 第一层幂等:Redis SETNX 快速拦截绝大多数重投(性能优化,不是最终保证)
            idemKey = "xp:mq:like:idem:" + msg.getActionId();
            Boolean first = redis.opsForValue().setIfAbsent(idemKey, "1", 10, TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(first)) {
                log.debug("duplicate like msg (redis), drop. actionId={}", msg.getActionId());
                return;
            }

            // 第二层幂等(最终保证):查当前明细状态,只有真正发生状态变化才调整计数。
            // 即使 Redis 键失效、消息重投,这里也能靠"状态比对 + 唯一约束"避免重复计数。
            Integer current = articleLikeMapper.selectStatus(msg.getUserId(), msg.getArticleId());
            if (current != null && current == newStatus) {
                // 状态没变(已经是点赞/已经是取消),说明是重复消息,不调整计数
                log.debug("status unchanged, skip count. user={} article={} status={}",
                        msg.getUserId(), msg.getArticleId(), newStatus);
                return;
            }

            // upsert 明细(唯一约束兜底,绝不会产生重复行)
            articleLikeMapper.upsertLike(msg.getUserId(), msg.getArticleId(), newStatus);

            // 状态确实变化了,才累加到 Redis 计数缓冲(批量落库)
            redis.opsForHash().increment(BUFFER_KEY, String.valueOf(msg.getArticleId()), msg.getDelta());

        } catch (Exception e) {
            if (idemKey != null) {
                try { redis.delete(idemKey); } catch (Exception ignore) {}
            }
            log.error("consume like msg failed", e);
            throw e; // 触发 MQ 重试
        }
    }

    private void flushSafe() {
        try { flush(); }
        catch (Exception e) { log.error("like flush failed", e); }
    }

    /**
     * 把 Redis 缓冲里的累计 delta 落库。
     * <p>用 rename 把缓冲 key 原子地"换走"再处理,避免处理期间新写入被一起删掉导致丢数据。
     */
    private synchronized void flush() {
        // 缓冲为空直接返回
        Boolean exist = redis.hasKey(BUFFER_KEY);
        if (!Boolean.TRUE.equals(exist)) return;

        // 原子换名:把当前缓冲改名到临时 key,新进来的 delta 写入新的 BUFFER_KEY,互不干扰
        String tmpKey = BUFFER_KEY + ":flushing:" + System.currentTimeMillis();
        try {
            redis.rename(BUFFER_KEY, tmpKey);
        } catch (Exception e) {
            // rename 失败(可能 key 刚好不存在),跳过本轮
            return;
        }

        Map<Object, Object> snapshot = redis.opsForHash().entries(tmpKey);
        if (snapshot.isEmpty()) { redis.delete(tmpKey); return; }

        long start = System.currentTimeMillis();
        int ok = 0;
        boolean allSuccess = true;
        for (Map.Entry<Object, Object> e : snapshot.entrySet()) {
            Long articleId = Long.valueOf(e.getKey().toString());
            long delta = Long.parseLong(e.getValue().toString());
            if (delta == 0) continue;
            try {
                articleMapper.incrLikeCount(articleId, delta);
                ok++;
            } catch (Exception ex) {
                allSuccess = false;
                log.error("incr like failed, articleId={}, delta={}", articleId, delta, ex);
                // 落库失败的 delta 累加回主缓冲,下轮重试(保证不丢)
                redis.opsForHash().increment(BUFFER_KEY, articleId.toString(), delta);
            }
        }
        // 处理完删除临时 key
        redis.delete(tmpKey);
        if (ok > 0) {
            log.info("like flush done, articles={}, cost={}ms, allSuccess={}",
                    ok, System.currentTimeMillis() - start, allSuccess);
        }
    }
}
