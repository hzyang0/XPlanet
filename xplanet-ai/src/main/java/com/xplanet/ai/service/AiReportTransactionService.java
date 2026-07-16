package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiReportRecord;
import com.xplanet.ai.persistence.AiResultMapper;
import com.xplanet.api.request.ReviewReportRequest;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiReportTransactionService {

    private final AiResultMapper resultMapper;

    @Transactional
    public AiReportRecord approve(Long userId, Long taskId, ReviewReportRequest request) {
        AiReportRecord report = resultMapper.findOwnedReport(taskId, userId);
        if (report == null) {
            throw new BizException(ErrorCode.AI_REPORT_NOT_READY);
        }
        if ("PUBLISHED".equals(report.getStatus())) {
            return report;
        }
        String title = request != null && request.getTitle() != null && !request.getTitle().isBlank()
                ? request.getTitle().trim() : report.getTitle();
        String content = request != null && request.getContent() != null && !request.getContent().isBlank()
                ? request.getContent() : report.getContent();
        if (title.length() > 500 || content.length() > 200000) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (resultMapper.approve(report.getId(), userId, title, content) <= 0) {
            throw new BizException(ErrorCode.AI_TASK_STATE_CONFLICT);
        }
        report.setStatus("APPROVED");
        report.setTitle(title);
        report.setContent(content);
        return report;
    }

    @Transactional
    public void markPublished(Long reportId, Long userId, Long articleId) {
        if (resultMapper.markPublished(reportId, userId, articleId) <= 0) {
            throw new IllegalStateException("failed to mark AI report published");
        }
    }
}
