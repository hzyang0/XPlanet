package com.xplanet.interaction.client;

import com.xplanet.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "article-service", url = "${article-service.base-url:http://localhost:8081}")
public interface ArticleServiceClient {

    @GetMapping("/api/article/{id}/exists")
    R<Boolean> exists(@PathVariable("id") Long articleId);
}
