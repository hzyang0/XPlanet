package com.xplanet.interaction.client;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleClient {

    private final ArticleServiceClient articleServiceClient;

    public boolean existsActive(Long articleId) {
        R<Boolean> response;
        try {
            response = articleServiceClient.exists(articleId);
        } catch (Exception e) {
            log.warn("article existence check failed, articleId={}, err={}", articleId, e.getMessage());
            throw new BizException(ErrorCode.ARTICLE_SERVICE_UNAVAILABLE);
        }
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            log.warn("article existence check returned invalid response, articleId={}", articleId);
            throw new BizException(ErrorCode.ARTICLE_SERVICE_UNAVAILABLE);
        }
        return response.getData();
    }
}
