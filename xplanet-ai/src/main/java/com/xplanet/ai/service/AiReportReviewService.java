package com.xplanet.ai.service;

import com.xplanet.ai.client.ArticlePublishClient;
import com.xplanet.ai.persistence.AiReportRecord;
import com.xplanet.api.request.AiReportPublishRequest;
import com.xplanet.api.request.ReviewReportRequest;
import com.xplanet.api.vo.AiReportVO;
import com.xplanet.api.vo.ArticlePublishResultVO;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiReportReviewService {

    private final AiReportTransactionService transactionService;
    private final AiReportQueryService queryService;
    private final ArticlePublishClient articleClient;
    private final InternalTokenVerifier internalToken;

    /**
     * Human approval is committed before the cross-service call. If article is unavailable, the report remains
     * APPROVED and the same endpoint can be retried; article uses reportId as a unique idempotency key.
     */
    public AiReportVO approveAndPublish(Long userId, Long taskId, ReviewReportRequest review) {
        AiReportRecord report = transactionService.approve(userId, taskId, review);
        if ("PUBLISHED".equals(report.getStatus())) {
            return queryService.get(userId, taskId);
        }
        AiReportPublishRequest request = new AiReportPublishRequest();
        request.setReportId(report.getId());
        request.setAuthorId(userId);
        request.setTitle(report.getTitle().substring(0, Math.min(200, report.getTitle().length())));
        request.setContent(report.getContent());
        request.setTags("ai,research");

        R<ArticlePublishResultVO> response;
        try {
            response = articleClient.publish(internalToken.value(), request);
        } catch (Exception e) {
            throw new BizException(ErrorCode.ARTICLE_SERVICE_UNAVAILABLE);
        }
        if (response == null || response.getCode() != 0 || response.getData() == null
                || response.getData().getArticleId() == null) {
            throw new BizException(ErrorCode.ARTICLE_SERVICE_UNAVAILABLE);
        }
        transactionService.markPublished(report.getId(), userId, response.getData().getArticleId());
        return queryService.get(userId, taskId);
    }
}
