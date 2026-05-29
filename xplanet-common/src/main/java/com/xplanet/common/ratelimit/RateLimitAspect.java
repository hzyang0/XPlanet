package com.xplanet.common.ratelimit;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Collections;

/**
 * 限流切面。拦截带 @RateLimit 的方法,用 Redis 做固定窗口计数限流。
 *
 * <h3>原理(固定窗口计数法)</h3>
 * <pre>
 * 对每个 (接口key + 维度) 维护一个 Redis 计数器,key 带上当前时间窗口。
 * 每次请求 INCR 计数:
 *   - 第一次设置过期时间为窗口长度(用 Lua 保证 INCR + EXPIRE 原子)
 *   - 计数 > limit 则拒绝(抛 FLOW_BLOCKED)
 * 窗口过期后计数器自动消失,自然进入下一个窗口。
 * </pre>
 *
 * <h3>为什么用 Lua 脚本</h3>
 * <p>"INCR 然后判断是否第一次再 EXPIRE" 是多步操作,并发下非原子会出问题
 * (比如 INCR 后还没 EXPIRE 就宕机,key 永不过期)。Lua 在 Redis 中原子执行,杜绝竞态。
 *
 * <h3>局限(面试可主动说)</h3>
 * <p>固定窗口有"临界问题":窗口边界前后各打满一次,瞬时可达 2 倍 limit。
 * 要更平滑可用滑动窗口或令牌桶(Redisson 的 RRateLimiter 就是令牌桶)。
 * 这里用固定窗口是因为实现最简单、够用,且便于讲清原理。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate redis;

    // INCR + 首次设过期,返回当前计数
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('incr', KEYS[1]) " +
            "if c == 1 then redis.call('expire', KEYS[1], ARGV[1]) end " +
            "return c", Long.class);

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String dimension = rateLimit.byIp() ? clientIp() : "global";
        long window = System.currentTimeMillis() / (rateLimit.windowSeconds() * 1000L);
        String redisKey = "xp:rl:" + rateLimit.key() + ":" + dimension + ":" + window;

        Long count = redis.execute(SCRIPT,
                Collections.singletonList(redisKey),
                String.valueOf(rateLimit.windowSeconds()));

        if (count != null && count > rateLimit.limit()) {
            log.warn("限流触发 key={} dimension={} count={}", rateLimit.key(), dimension, count);
            throw new BizException(ErrorCode.FLOW_BLOCKED);
        }
        return pjp.proceed();
    }

    private String clientIp() {
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr != null) {
                HttpServletRequest req = attr.getRequest();
                String xff = req.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
                return req.getRemoteAddr();
            }
        } catch (Exception ignore) {}
        return "unknown";
    }
}
