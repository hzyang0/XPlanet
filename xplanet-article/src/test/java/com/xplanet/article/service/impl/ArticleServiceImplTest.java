package com.xplanet.article.service.impl;

import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.article.cache.ArticleCacheManager;
import com.xplanet.article.entity.Article;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.article.outbox.ArticleChangeOutboxMapper;
import com.xplanet.article.service.UserClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticleServiceImplTest {

    private final ArticleMapper articleMapper = mock(ArticleMapper.class);
    private final ArticleCacheManager cacheManager = mock(ArticleCacheManager.class);
    private final ArticleChangeOutboxMapper outboxMapper = mock(ArticleChangeOutboxMapper.class);
    private final ArticleServiceImpl articleService = new ArticleServiceImpl(
            articleMapper,
            cacheManager,
            outboxMapper,
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

    @Test
    void shouldPersistImmediateAndDelayedInvalidationWhenUpdating() {
        Article article = article(9L, 1L);
        when(articleMapper.selectById(9L)).thenReturn(article);
        when(outboxMapper.insertEvent(anyString(), eq(9L), eq("UPDATE"), eq(0))).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(9L), eq("UPDATE"), eq(1))).thenReturn(1);

        articleService.update(1L, 9L, request());

        verify(articleMapper).updateById(article);
        verify(cacheManager).invalidate(9L);
        verify(outboxMapper).insertEvent(anyString(), eq(9L), eq("UPDATE"), eq(0));
        verify(outboxMapper).insertEvent(anyString(), eq(9L), eq("UPDATE"), eq(1));
    }

    @Test
    void shouldFailUpdateWhenDelayedInvalidationCannotBePersisted() {
        when(articleMapper.selectById(9L)).thenReturn(article(9L, 1L));
        when(outboxMapper.insertEvent(anyString(), eq(9L), eq("UPDATE"), eq(0))).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(9L), eq("UPDATE"), eq(1))).thenReturn(0);

        assertThatThrownBy(() -> articleService.update(1L, 9L, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed to persist delayed cache invalidation");
    }

    private Article article(Long id, Long authorId) {
        Article article = new Article();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setDeleted(0);
        return article;
    }

    private ArticlePublishRequest request() {
        ArticlePublishRequest request = new ArticlePublishRequest();
        request.setTitle("updated title");
        request.setContent("updated content");
        request.setTags("cache");
        return request;
    }
}
