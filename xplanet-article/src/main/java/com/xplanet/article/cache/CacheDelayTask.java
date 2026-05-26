package com.xplanet.article.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 缓存延迟任务,承载延迟双删的「第二删」。
 *
 * <p>为什么单独抽一个 Bean:
 * {@code @Async} 基于 Spring AOP 代理实现,<b>同类内部 this 调用不会走代理,注解失效</b>。
 * 把异步方法放到独立 Bean,由 ArticleServiceImpl 注入后跨 Bean 调用,代理才能生效,
 * 第二删才真正异步执行,不会阻塞业务线程、不会拉长数据库事务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheDelayTask {

    private final ArticleCacheManager cacheManager;

    /**
     * 延迟一段时间后再删一次缓存(延迟双删的第二删)。
     *
     * <p>延迟的目的:杀掉「读到旧值的线程在第一删之后又把旧值写回缓存」的竞态窗口。
     * 延迟时长应 ≥ 一次回源读 + 写缓存的耗时,这里取经验值 1s。
     *
     * <p>注意:调用方应在<b>数据库事务提交后</b>再触发本方法,避免延长事务持有连接的时间。
     */
    @Async("cacheTaskExecutor")
    public void secondDelete(Long articleId) {
        try {
            Thread.sleep(1000);
            cacheManager.invalidate(articleId);
            log.debug("second-delete done for articleId={}", articleId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
