package com.xplanet.ai.client;

import com.xplanet.api.request.AiReportPublishRequest;
import com.xplanet.api.vo.ArticlePublishResultVO;
import com.xplanet.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "article-publish-service", url = "${article-service.base-url:http://localhost:8081}")
public interface ArticlePublishClient {

    @PostMapping("/internal/article/ai-reports")
    R<ArticlePublishResultVO> publish(@RequestHeader("X-Agent-Token") String internalToken,
                                      @RequestBody AiReportPublishRequest request);
}
