package com.xplanet.article.controller;

import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.api.vo.ArticleListItemVO;
import com.xplanet.article.service.ArticleService;
import com.xplanet.common.response.PageResult;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/article")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;
    private final com.xplanet.article.hot.HotRankService hotRankService;

    @GetMapping("/{id}")
    @com.xplanet.common.ratelimit.RateLimit(key = "article_detail", limit = 100, windowSeconds = 1)
    public R<ArticleDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(articleService.getDetail(id));
    }

    @GetMapping("/list")
    public R<PageResult<ArticleListItemVO>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(articleService.list(pageNum, pageSize));
    }

    /** 热门文章 Top N(Redis ZSet 实现,定时刷新) */
    @GetMapping("/hot")
    public R<java.util.List<ArticleListItemVO>> hot(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(hotRankService.getTopN(limit));
    }

    @PostMapping("/publish")
    public R<Long> publish(@Valid @RequestBody ArticlePublishRequest req) {
        return R.ok(articleService.publish(com.xplanet.common.auth.UserContext.getUserId(), req));
    }

    @PutMapping("/{id}")
    public R<Void> update(
            @PathVariable Long id,
            @Valid @RequestBody ArticlePublishRequest req) {
        articleService.update(com.xplanet.common.auth.UserContext.getUserId(), id, req);
        return R.ok(null);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        articleService.delete(com.xplanet.common.auth.UserContext.getUserId(), id);
        return R.ok(null);
    }
}
