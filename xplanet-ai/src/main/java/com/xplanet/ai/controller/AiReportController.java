package com.xplanet.ai.controller;

import com.xplanet.ai.service.AiReportQueryService;
import com.xplanet.ai.service.AiReportReviewService;
import com.xplanet.api.request.ReviewReportRequest;
import com.xplanet.api.vo.AiReportVO;
import com.xplanet.common.auth.UserContext;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/ai/tasks/{taskId}/report")
@RequiredArgsConstructor
public class AiReportController {

    private final AiReportQueryService queryService;
    private final AiReportReviewService reviewService;

    @GetMapping
    public R<AiReportVO> get(@PathVariable Long taskId) {
        return R.ok(queryService.get(UserContext.getUserId(), taskId));
    }

    @PostMapping("/approve")
    public R<AiReportVO> approve(@PathVariable Long taskId,
                                 @Valid @RequestBody(required = false) ReviewReportRequest request) {
        return R.ok(reviewService.approveAndPublish(UserContext.getUserId(), taskId, request));
    }
}
