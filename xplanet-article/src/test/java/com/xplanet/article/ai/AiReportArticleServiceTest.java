package com.xplanet.article.ai;

import com.xplanet.api.request.AiReportPublishRequest;
import com.xplanet.api.vo.ArticlePublishResultVO;
import com.xplanet.article.entity.Article;
import com.xplanet.article.mapper.ArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiReportArticleServiceTest {

    private AiPublishedArticleMapper projectionMapper;
    private ArticleMapper articleMapper;
    private AiReportArticleService service;

    @BeforeEach
    void setUp() {
        projectionMapper = mock(AiPublishedArticleMapper.class);
        articleMapper = mock(ArticleMapper.class);
        service = new AiReportArticleService(projectionMapper, articleMapper);
    }

    @Test
    void shouldCreateOneArticleAndProjection() {
        when(projectionMapper.findArticleId(10L)).thenReturn(null);
        doAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            article.setId(88L);
            return 1;
        }).when(articleMapper).insert(any(Article.class));
        when(projectionMapper.insert(10L, 88L)).thenReturn(1);

        ArticlePublishResultVO result = service.publish(request());

        assertThat(result.getArticleId()).isEqualTo(88L);
        assertThat(result.isCreated()).isTrue();
    }

    @Test
    void shouldReturnExistingArticleForRepeatedReportPublish() {
        when(projectionMapper.findArticleId(10L)).thenReturn(88L);

        ArticlePublishResultVO result = service.publish(request());

        assertThat(result.getArticleId()).isEqualTo(88L);
        assertThat(result.isCreated()).isFalse();
        verify(articleMapper, never()).insert(any());
    }

    @Test
    void shouldRollbackArticleWhenConcurrentProjectionWins() {
        when(projectionMapper.findArticleId(10L)).thenReturn(null);
        doAnswer(invocation -> {
            Article article = invocation.getArgument(0);
            article.setId(89L);
            return 1;
        }).when(articleMapper).insert(any(Article.class));
        when(projectionMapper.insert(10L, 89L)).thenReturn(0);

        assertThatThrownBy(() -> service.publish(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI report was concurrently published");
    }

    private AiReportPublishRequest request() {
        AiReportPublishRequest request = new AiReportPublishRequest();
        request.setReportId(10L);
        request.setAuthorId(7L);
        request.setTitle("AI report");
        request.setContent("content");
        return request;
    }
}
