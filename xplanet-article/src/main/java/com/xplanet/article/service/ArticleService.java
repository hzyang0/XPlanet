package com.xplanet.article.service;

import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.api.vo.ArticleListItemVO;
import com.xplanet.common.response.PageResult;

public interface ArticleService {

    ArticleDetailVO getDetail(Long articleId);

    /** 供内部轻量校验使用，不加载正文、作者和缓存。 */
    boolean existsActive(Long articleId);

    /** 分页查询文章列表(按创建时间倒序) */
    PageResult<ArticleListItemVO> list(int pageNum, int pageSize);

    Long publish(Long authorId, ArticlePublishRequest req);

    void update(Long authorId, Long articleId, ArticlePublishRequest req);

    void delete(Long authorId, Long articleId);
}
