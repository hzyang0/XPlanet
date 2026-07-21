package com.xplanet.article.search;

import com.xplanet.article.mapper.ArticleMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleKnowledgeSearchServiceTest {

    @Test
    void shouldNormalizeQueryAndClampTopK() {
        ArticleMapper mapper = mock(ArticleMapper.class);
        when(mapper.searchKnowledge("agent checkpoint", 10)).thenReturn(List.of());
        ArticleKnowledgeSearchService service = new ArticleKnowledgeSearchService(mapper);

        assertThat(service.search("  agent   checkpoint  ", 99)).isEmpty();

        verify(mapper).searchKnowledge("agent checkpoint", 10);
    }

    @Test
    void shouldRejectBlankOrOversizedQuery() {
        ArticleKnowledgeSearchService service = new ArticleKnowledgeSearchService(mock(ArticleMapper.class));

        assertThatThrownBy(() -> service.search("  ", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("x".repeat(301), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryMustFilterDeletedArticlesAndUseBoundLimit() throws Exception {
        Method method = ArticleMapper.class.getMethod("searchKnowledge", String.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("deleted=0");
        assertThat(sql).contains("LIMIT #{topK}");
        assertThat(sql).contains("MATCH(title, content)");
    }
}
