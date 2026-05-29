package com.xplanet.common.ratelimit;

import java.lang.annotation.*;

/**
 * 接口限流注解。加在 Controller 方法上,限制单位时间内的访问次数。
 *
 * <p>用法示例:
 * <pre>{@code
 * @RateLimit(key = "article_detail", limit = 100, windowSeconds = 1)
 * public R<ArticleDetailVO> detail(...) { ... }
 * }</pre>
 *
 * <p>这是一个轻量的自研限流,基于 Redis 计数实现。
 * 相比引入 Sentinel,优点是简单、零额外组件、原理透明、便于讲清楚。
 * 缺点是功能不如 Sentinel 丰富(没有熔断、热点参数、规则动态下发),
 * 但对"防接口被刷"这个核心诉求已经够用。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 限流维度的 key 前缀(区分不同接口) */
    String key();

    /** 时间窗口内允许的最大请求数 */
    int limit() default 100;

    /** 时间窗口大小(秒) */
    int windowSeconds() default 1;

    /** 限流维度:true=按调用方IP分别限流,false=接口全局限流 */
    boolean byIp() default true;
}
