package com.xplanet.seckill.service;

import com.xplanet.seckill.support.SeckillRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

/** Redis remains the admission gate; MySQL remains the final inventory authority. */
@Service @RequiredArgsConstructor
public class RedisReservationService {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>(
            "local stock=tonumber(redis.call('GET',KEYS[1]) or '-1'); " +
            "if stock<0 then return -1 end; " +
            "if redis.call('SISMEMBER',KEYS[2],ARGV[1])==1 then return 2 end; " +
            "if stock<=0 then return 1 end; " +
            "redis.call('DECR',KEYS[1]); redis.call('SADD',KEYS[2],ARGV[1]); return 0;", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('SREM',KEYS[2],ARGV[1])==1 then return redis.call('INCR',KEYS[1]) end; return 0;", Long.class);
    private final StringRedisTemplate redis;
    public long reserve(Long activityId, Long userId) {
        Long result=redis.execute(RESERVE, List.of(SeckillRedisKeys.stock(activityId), SeckillRedisKeys.buyers(activityId)), String.valueOf(userId));
        return result == null ? -1 : result;
    }
    public void release(Long activityId, Long userId) {
        redis.execute(RELEASE, List.of(SeckillRedisKeys.stock(activityId), SeckillRedisKeys.buyers(activityId)), String.valueOf(userId));
    }
    public void warm(Long activityId, int stock) {
        // A running activity must never be reset by a scheduled warm-up: doing so would
        // erase buyer markers and reintroduce duplicate purchase opportunities.
        redis.opsForValue().setIfAbsent(SeckillRedisKeys.stock(activityId), String.valueOf(stock));
    }
}
