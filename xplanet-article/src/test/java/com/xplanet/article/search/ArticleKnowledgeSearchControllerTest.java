package com.xplanet.article.search;

import com.xplanet.article.ai.ArticleInternalTokenVerifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ArticleKnowledgeSearchControllerTest {

    @Test
    void shouldVerifyInternalTokenBeforeSearching() {
        ArticleInternalTokenVerifier verifier = mock(ArticleInternalTokenVerifier.class);
        ArticleKnowledgeSearchService service = mock(ArticleKnowledgeSearchService.class);
        ArticleKnowledgeSearchController controller =
                new ArticleKnowledgeSearchController(verifier, service);

        controller.search("valid-token", "checkpoint", 5);

        verify(verifier).require("valid-token");
        verify(service).search("checkpoint", 5);
    }

    @Test
    void shouldNotSearchWhenInternalTokenIsRejected() {
        ArticleInternalTokenVerifier verifier = mock(ArticleInternalTokenVerifier.class);
        ArticleKnowledgeSearchService service = mock(ArticleKnowledgeSearchService.class);
        doThrow(new SecurityException("invalid internal token")).when(verifier).require("wrong");
        ArticleKnowledgeSearchController controller =
                new ArticleKnowledgeSearchController(verifier, service);

        assertThatThrownBy(() -> controller.search("wrong", "checkpoint", 5))
                .isInstanceOf(SecurityException.class);

        verify(service, never()).search("checkpoint", 5);
    }
}
