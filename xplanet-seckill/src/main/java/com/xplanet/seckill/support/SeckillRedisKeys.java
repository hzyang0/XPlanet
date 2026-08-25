package com.xplanet.seckill.support;
public final class SeckillRedisKeys {
    private SeckillRedisKeys() {}
    public static String stock(Long activityId) { return "xp:seckill:stock:" + activityId; }
    public static String buyers(Long activityId) { return "xp:seckill:buyers:" + activityId; }
}
