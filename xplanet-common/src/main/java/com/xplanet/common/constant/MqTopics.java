package com.xplanet.common.constant;

public final class MqTopics {
    private MqTopics() {}

    /** 点赞消息: 异步落库 */
    public static final String TOPIC_LIKE = "xp_like_topic";
    public static final String TAG_LIKE_ADD = "ADD";
    public static final String TAG_LIKE_CANCEL = "CANCEL";

    /** 文章变更: 触发缓存清理(双删第二删 + 兜底) */
    public static final String TOPIC_ARTICLE_CHANGE = "xp_article_change_topic";

    /** 消费者组 */
    public static final String GROUP_LIKE_CONSUMER = "xp_like_consumer_group";
    public static final String GROUP_ARTICLE_CACHE_CONSUMER = "xp_article_cache_consumer_group";
}
