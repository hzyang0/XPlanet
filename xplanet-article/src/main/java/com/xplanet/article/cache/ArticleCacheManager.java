package com.xplanet.article.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.common.constant.CacheKeys;
import com.xplanet.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 文章详情二级缓存管理器。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>L1 = Caffeine 本地缓存</b>: 容量 10000, expireAfterWrite 30s。
 *       本地缓存只承接读流量,且 TTL 短,目的是: 削减 Redis 网络往返 + 抗热 Key。</li>
 *   <li><b>L2 = Redis 分布式缓存</b>: TTL 30min + 随机抖动,避免缓存雪崩。</li>
 *   <li><b>击穿保护</b>: L1/L2 都未命中时,对同一篇文章用 Redisson 分布式锁串行化重建。</li>
 *   <li><b>穿透保护</b>: 数据库查到 null 时回写一个特殊空标记 "__EMPTY__",TTL 60s。</li>
 *   <li><b>失效</b>: 提供 invalidate() 同时清 L1 + L2; L1 通过 RocketMQ 广播保证多实例一致。</li>
 * </ul>
 *
 * <h3>为什么 L1 TTL 这么短(30s)?</h3>
 * <p>L1 是节点本地的,失效通知通过广播 MQ 异步推送,存在窗口期。
 * 短 TTL 把"最坏不一致窗口"压缩在用户可接受范围,作为兜底。
 *
 * <h3>为什么不用 Spring Cache 注解?</h3>
 * <p>Spring Cache 在二级缓存、空值标记、分布式锁重建、随机 TTL 这些场景下定制困难,
 * 自己实现可控性更高,也是简历能讲清楚的关键。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleCacheManager {

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;

    /** L1 本地缓存 */
    private Cache<Long, String> localCache;

    /** L2 Redis TTL 基线(秒),实际 TTL = 基线 + 随机抖动 */
    private static final long REDIS_TTL_BASE_SEC = 30 * 60;
    private static final long REDIS_TTL_JITTER_SEC = 5 * 60;
    private static final long EMPTY_TTL_SEC = 60;

    /** 分布式锁等待 / 持有时间 */
    private static final long LOCK_WAIT_MS = 200;
    private static final long LOCK_LEASE_MS = 3000;

    @PostConstruct
    public void init() {
        this.localCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .recordStats() // 暴露命中率给 Micrometer
                .build();
        log.info("ArticleCacheManager initialized, L1 capacity=10000, ttl=30s");
    }

    /**
     * 读取文章详情。
     *
     * @param articleId 文章 ID
     * @param loader    回源函数(查 DB)
     */
    public ArticleDetailVO get(Long articleId, Function<Long, ArticleDetailVO> loader) {
        String key = CacheKeys.articleDetail(articleId);

        // L1
        String l1 = localCache.getIfPresent(articleId);
        if (l1 != null) {
            return decode(l1);
        }

        // L2
        String l2 = redis.opsForValue().get(key);
        if (l2 != null) {
            localCache.put(articleId, l2);
            return decode(l2);
        }

        // 击穿保护: 锁重建
        return rebuild(articleId, key, loader);
    }

    /**
     * 锁重建,只放一个线程进 DB,其它线程读重建结果。
     */
    private ArticleDetailVO rebuild(Long articleId, String key, Function<Long, ArticleDetailVO> loader) {
        RLock lock = redisson.getLock(CacheKeys.articleRebuildLock(articleId));
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
            if (!locked) {
                // 抢锁失败: 等待后再次尝试读 L2(可能已被持锁线程回填)
                Thread.sleep(50);
                String again = redis.opsForValue().get(key);
                if (again != null) {
                    localCache.put(articleId, again);
                    return decode(again);
                }
                // 实在拿不到: 直接回源(降级,但比一直等好)
                log.warn("rebuild lock failed for articleId={}, fallback to direct DB", articleId);
                return loader.apply(articleId);
            }

            // 持锁成功后 double check L2(避免多线程都抢到锁后重复回源)
            String dc = redis.opsForValue().get(key);
            if (dc != null) {
                localCache.put(articleId, dc);
                return decode(dc);
            }

            // 真正回源
            ArticleDetailVO vo = loader.apply(articleId);
            if (vo == null) {
                // 穿透保护
                redis.opsForValue().set(key, CacheKeys.EMPTY_VALUE, EMPTY_TTL_SEC, TimeUnit.SECONDS);
                return null;
            }

            String json = JsonUtil.toJson(vo);
            long ttl = REDIS_TTL_BASE_SEC + ThreadLocalRandom.current().nextLong(REDIS_TTL_JITTER_SEC);
            redis.opsForValue().set(key, json, ttl, TimeUnit.SECONDS);
            localCache.put(articleId, json);
            return vo;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("rebuild interrupted, articleId={}", articleId, e);
            return loader.apply(articleId);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 失效本地 + Redis。
     * <p>注意: 调用方负责广播给其他实例(通过 MQ),这里只处理本节点 + Redis。
     */
    public void invalidate(Long articleId) {
        localCache.invalidate(articleId);
        redis.delete(CacheKeys.articleDetail(articleId));
        log.info("invalidated L1+L2 for articleId={}", articleId);
    }

    /**
     * 只失效本地缓存(MQ 广播消费者调用)。
     */
    public void invalidateLocal(Long articleId) {
        localCache.invalidate(articleId);
        log.debug("invalidated L1 for articleId={}", articleId);
    }

    private ArticleDetailVO decode(String json) {
        if (CacheKeys.EMPTY_VALUE.equals(json)) {
            return null;
        }
        return JsonUtil.fromJson(json, ArticleDetailVO.class);
    }

    /** 暴露 stats 给 Prometheus 抓取 */
    public Cache<Long, String> getLocalCache() {
        return localCache;
    }
}
