package com.xplanet.article.mq;

import com.xplanet.api.dto.ArticleChangeMessage;
import com.xplanet.article.cache.ArticleCacheManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ArticleCacheInvalidatorTest {

    private final ArticleCacheManager cacheManager = mock(ArticleCacheManager.class);
    private final ArticleCacheInvalidator invalidator = new ArticleCacheInvalidator(cacheManager);

    @Test
    void shouldInvalidateLocalAndSharedCache() {
        invalidator.onMessage(ArticleChangeMessage.builder()
                .eventId("event-1")
                .articleId(9L)
                .op("UPDATE")
                .build());

        verify(cacheManager).invalidate(9L);
    }

    @Test
    void shouldRejectMalformedEvent() {
        assertThatThrownBy(() -> invalidator.onMessage(new ArticleChangeMessage()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
