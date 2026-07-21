package com.xplanet.article.search;

import com.xplanet.api.vo.InternalArticleSearchVO;
import com.xplanet.article.ai.ArticleInternalTokenVerifier;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/article/knowledge")
@RequiredArgsConstructor
public class ArticleKnowledgeSearchController {

    private final ArticleInternalTokenVerifier tokenVerifier;
    private final ArticleKnowledgeSearchService searchService;

    @GetMapping("/search")
    public R<List<InternalArticleSearchVO>> search(
            @RequestHeader("X-Agent-Token") String token,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        tokenVerifier.require(token);
        return R.ok(searchService.search(query, topK));
    }
}
