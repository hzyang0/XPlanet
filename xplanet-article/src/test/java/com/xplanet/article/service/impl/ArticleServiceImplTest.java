package com.xplanet.article.service.impl;

import com.xplanet.article.cache.ArticleCacheManager;
import com.xplanet.article.cache.CacheDelayTask;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.article.service.UserClient;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticleServiceImplTest {

    private final ArticleMapper articleMapper = mock(ArticleMapper.class);
    private final ArticleServiceImpl articleService = new ArticleServiceImpl(
            articleMapper,
            mock(ArticleCacheManager.class),
            mock(RocketMQTemplate.class),
            mock(CacheDelayTask.class),
            mock(UserClient.class));

    @Test
    void shouldCheckOnlyActiveArticleForInternalExistenceQuery() {
        when(articleMapper.existsActive(9L)).thenReturn(true);

        assertThat(articleService.existsActive(9L)).isTrue();
        verify(articleMapper).existsActive(9L);
    }

    @Test
    void shouldRejectInvalidIdWithoutDatabaseCall() {
        assertThat(articleService.existsActive(0L)).isFalse();
        assertThat(articleService.existsActive(null)).isFalse();
        verifyNoInteractions(articleMapper);
    }
}
