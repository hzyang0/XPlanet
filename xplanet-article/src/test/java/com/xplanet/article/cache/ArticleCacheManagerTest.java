package com.xplanet.article.cache;

import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.common.constant.CacheKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ArticleCacheManagerTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedissonClient redisson;
    private RLock lock;
    private ArticleCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getLock(CacheKeys.articleRebuildLock(9L))).thenReturn(lock);

        cacheManager = new ArticleCacheManager(redis, redisson);
        cacheManager.init();
    }

    @Test
    void shouldUseWatchdogEnabledTryLockOverloadWhenRebuilding() throws InterruptedException {
        ArticleDetailVO article = new ArticleDetailVO();
        article.setId(9L);
        article.setTitle("watchdog");
        Function<Long, ArticleDetailVO> loader = mock(Function.class);
        when(loader.apply(9L)).thenReturn(article);
        when(lock.tryLock(200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(cacheManager.get(9L, loader)).isSameAs(article);

        verify(lock).tryLock(200L, TimeUnit.MILLISECONDS);
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        verify(lock).unlock();
        verify(values).set(any(), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void shouldServeRebuiltValueFromLocalCacheOnNextRead() throws InterruptedException {
        ArticleDetailVO article = new ArticleDetailVO();
        article.setId(9L);
        Function<Long, ArticleDetailVO> loader = mock(Function.class);
        when(loader.apply(9L)).thenReturn(article);
        when(lock.tryLock(200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(cacheManager.get(9L, loader)).isSameAs(article);
        assertThat(cacheManager.get(9L, loader).getId()).isEqualTo(9L);

        verify(loader, times(1)).apply(9L);
        verify(lock, times(1)).tryLock(200L, TimeUnit.MILLISECONDS);
    }
}
