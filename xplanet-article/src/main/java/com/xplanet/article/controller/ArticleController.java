package com.xplanet.article.controller;

import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.article.service.ArticleService;
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

    @GetMapping("/{id}")
    public R<ArticleDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(articleService.getDetail(id));
    }

    @PostMapping("/publish")
    public R<Long> publish(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @Valid @RequestBody ArticlePublishRequest req) {
        return R.ok(articleService.publish(userId, req));
    }

    @PutMapping("/{id}")
    public R<Void> update(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody ArticlePublishRequest req) {
        articleService.update(userId, id, req);
        return R.ok(null);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long id) {
        articleService.delete(userId, id);
        return R.ok(null);
    }
}
