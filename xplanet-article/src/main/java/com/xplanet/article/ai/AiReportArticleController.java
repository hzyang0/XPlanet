package com.xplanet.article.ai;

import com.xplanet.api.request.AiReportPublishRequest;
import com.xplanet.api.vo.ArticlePublishResultVO;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/internal/article/ai-reports")
@RequiredArgsConstructor
public class AiReportArticleController {

    private final ArticleInternalTokenVerifier tokenVerifier;
    private final AiReportArticleService service;

    @PostMapping
    public R<ArticlePublishResultVO> publish(@RequestHeader("X-Agent-Token") String token,
                                             @Valid @RequestBody AiReportPublishRequest request) {
        tokenVerifier.require(token);
        return R.ok(service.publish(request));
    }
}
