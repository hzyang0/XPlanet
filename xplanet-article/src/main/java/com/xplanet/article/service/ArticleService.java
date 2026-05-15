package com.xplanet.article.service;

import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;

public interface ArticleService {

    ArticleDetailVO getDetail(Long articleId);

    Long publish(Long authorId, ArticlePublishRequest req);

    void update(Long authorId, Long articleId, ArticlePublishRequest req);

    void delete(Long authorId, Long articleId);
}
