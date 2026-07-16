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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiReportReviewServiceTest {

    private AiReportTransactionService tx;
    private AiReportQueryService query;
    private ArticlePublishClient articleClient;
    private AiReportReviewService service;

    @BeforeEach
    void setUp() {
        tx = mock(AiReportTransactionService.class);
        query = mock(AiReportQueryService.class);
        articleClient = mock(ArticlePublishClient.class);
        service = new AiReportReviewService(tx, query, articleClient,
                new InternalTokenVerifier("x".repeat(32)));
    }

    @Test
    void shouldPublishApprovedReportAndPersistArticleId() {
        AiReportRecord report = report("APPROVED");
        when(tx.approve(eq(7L), eq(1L), any())).thenReturn(report);
        when(articleClient.publish(eq("x".repeat(32)), any(AiReportPublishRequest.class)))
                .thenReturn(R.ok(new ArticlePublishResultVO(99L, true)));
        AiReportVO expected = new AiReportVO();
        expected.setPublishArticleId(99L);
        when(query.get(7L, 1L)).thenReturn(expected);

        assertThat(service.approveAndPublish(7L, 1L, new ReviewReportRequest())
                .getPublishArticleId()).isEqualTo(99L);

        verify(tx).markPublished(10L, 7L, 99L);
    }

    @Test
    void shouldLeaveLocalApprovalRetryableWhenArticleIsDown() {
        when(tx.approve(eq(7L), eq(1L), any())).thenReturn(report("APPROVED"));
        when(articleClient.publish(eq("x".repeat(32)), any())).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> service.approveAndPublish(7L, 1L, new ReviewReportRequest()))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.ARTICLE_SERVICE_UNAVAILABLE.getCode());

        verify(tx, never()).markPublished(any(), any(), any());
    }

    private AiReportRecord report(String status) {
        AiReportRecord report = new AiReportRecord();
        report.setId(10L);
        report.setTaskId(1L);
        report.setStatus(status);
        report.setTitle("Report");
        report.setContent("Content");
        return report;
    }
}
