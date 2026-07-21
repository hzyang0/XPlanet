package com.xplanet.article.search;

import com.xplanet.api.vo.InternalArticleSearchVO;
import com.xplanet.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ArticleKnowledgeSearchService {

    static final int MAX_TOP_K = 10;

    private final ArticleMapper articleMapper;

    public List<InternalArticleSearchVO> search(String rawQuery, int requestedTopK) {
        String query = rawQuery == null ? "" : rawQuery.replaceAll("\\s+", " ").trim();
        if (query.isEmpty() || query.length() > 300) {
            throw new IllegalArgumentException("internal search query must contain 1..300 characters");
        }
        int topK = requestedTopK < 1 ? 5 : Math.min(requestedTopK, MAX_TOP_K);
        return articleMapper.searchKnowledge(query, topK);
    }
}
