package com.xplanet.article.ai;

import com.xplanet.api.request.AiReportPublishRequest;
import com.xplanet.api.vo.ArticlePublishResultVO;
import com.xplanet.article.entity.Article;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiReportArticleService {

    private final AiPublishedArticleMapper projectionMapper;
    private final ArticleMapper articleMapper;

    @Transactional
    public ArticlePublishResultVO publish(AiReportPublishRequest request) {
        validate(request);
        Long existing = projectionMapper.findArticleId(request.getReportId());
        if (existing != null) {
            return new ArticlePublishResultVO(existing, false);
        }

        Article article = new Article();
        article.setAuthorId(request.getAuthorId());
        article.setTitle(request.getTitle().trim());
        article.setContent(request.getContent());
        article.setTags(request.getTags() == null ? "ai,research" : request.getTags());
        article.setLikeCount(0L);
        article.setViewCount(0L);
        article.setDeleted(0);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        if (articleMapper.insert(article) != 1 || article.getId() == null) {
            throw new IllegalStateException("failed to create article from AI report");
        }
        if (projectionMapper.insert(request.getReportId(), article.getId()) != 1) {
            // A concurrent publisher won the reportId unique key; rolling back prevents a duplicate article.
            throw new IllegalStateException("AI report was concurrently published");
        }
        return new ArticlePublishResultVO(article.getId(), true);
    }

    private void validate(AiReportPublishRequest request) {
        if (request == null || request.getReportId() == null || request.getReportId() <= 0
                || request.getAuthorId() == null || request.getAuthorId() <= 0
                || request.getTitle() == null || request.getTitle().isBlank()
                || request.getTitle().length() > 200 || request.getContent() == null
                || request.getContent().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }
}
