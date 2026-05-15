package com.xplanet.article.cache;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 把 Caffeine 命中率、加载次数等指标接入 Prometheus。
 * 这样面试官问"你怎么知道 L1 起作用了" — 你能给监控截图。
 */
@Component
@RequiredArgsConstructor
public class CacheMetrics {

    private final ArticleCacheManager cacheManager;
    private final MeterRegistry registry;

    @EventListener(ContextRefreshedEvent.class)
    public void register() {
        Gauge.builder("xplanet.article.cache.l1.hit.count",
                () -> stats().hitCount())
                .description("L1 hit count")
                .register(registry);

        Gauge.builder("xplanet.article.cache.l1.miss.count",
                () -> stats().missCount())
                .description("L1 miss count")
                .register(registry);

        Gauge.builder("xplanet.article.cache.l1.hit.rate",
                () -> stats().hitRate())
                .description("L1 hit rate")
                .register(registry);

        Gauge.builder("xplanet.article.cache.l1.size",
                () -> cacheManager.getLocalCache().estimatedSize())
                .description("L1 estimated size")
                .register(registry);
    }

    private CacheStats stats() {
        return cacheManager.getLocalCache().stats();
    }
}
