package com.xplanet.common.constant;

/**
 * 缓存 Key 集中管理。
 * <p>原则:
 * 1. 所有 Key 用统一前缀 "xp:",方便在 redis-cli scan 时区分;
 * 2. 二级层级用冒号,符合 RedisInsight / 阿里规范;
 * 3. 抽成方法 + 静态常量,避免到处拼字符串导致拼错难排查。
 */
public final class CacheKeys {

    private CacheKeys() {}

    /** 文章详情缓存: xp:article:detail:{id} */
    public static final String ARTICLE_DETAIL_PREFIX = "xp:article:detail:";

    public static String articleDetail(Long articleId) {
        return ARTICLE_DETAIL_PREFIX + articleId;
    }

    /** 文章热点保护用的本地锁 key */
    public static String articleRebuildLock(Long articleId) {
        return "xp:lock:article:rebuild:" + articleId;
    }

    /** 点赞幂等: xp:like:idem:{userId}:{articleId} */
    public static String likeIdempotent(Long userId, Long articleId) {
        return "xp:like:idem:" + userId + ":" + articleId;
    }

    /** 文章点赞计数(累加缓冲,定期落库): xp:article:like:cnt:{articleId} */
    public static String articleLikeCount(Long articleId) {
        return "xp:article:like:cnt:" + articleId;
    }

    /** 用户对文章已点赞标记位(Set): xp:user:liked:{userId} */
    public static String userLikedSet(Long userId) {
        return "xp:user:liked:" + userId;
    }

    /** 空值缓存标记 */
    public static final String EMPTY_VALUE = "__EMPTY__";
}
