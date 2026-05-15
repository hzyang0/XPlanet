package com.xplanet.article.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.article.service.ArticleService;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
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

    /**
     * 文章详情接口。
     *
     * <p>使用 Sentinel @SentinelResource:
     * - blockHandler: 限流/熔断时返回降级
     * - fallback: 业务异常(BizException 除外)时返回降级
     * <p>
     * 热点参数限流规则在 SentinelConfig 中配置(基于 articleId 维度)。
     */
    @GetMapping("/{id}")
    @SentinelResource(
            value = "article:detail",
            blockHandler = "detailBlockHandler",
            fallback = "detailFallback",
            exceptionsToIgnore = {BizException.class}
    )
    public R<ArticleDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(articleService.getDetail(id));
    }

    /**
     * 限流/熔断降级。
     * 注意 blockHandler 方法签名要与原方法一致 + 末尾追加 BlockException。
     */
    public R<ArticleDetailVO> detailBlockHandler(Long id, BlockException ex) {
        log.warn("article detail blocked by sentinel, id={}, rule={}",
                id, ex.getClass().getSimpleName());
        return R.fail(ErrorCode.FLOW_BLOCKED);
    }

    /**
     * 业务降级(Redis 挂、DB 慢都会兜到这里)。
     * 真实生产可以返回上次的旧值或精简数据,这里返回错误码 + 提示。
     */
    public R<ArticleDetailVO> detailFallback(Long id, Throwable ex) {
        log.warn("article detail fallback, id={}", id, ex);
        return R.fail(ErrorCode.DEGRADE_FALLBACK);
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
