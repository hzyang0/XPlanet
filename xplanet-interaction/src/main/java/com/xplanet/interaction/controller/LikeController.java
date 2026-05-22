package com.xplanet.interaction.controller;

import com.xplanet.common.response.R;
import com.xplanet.interaction.service.LikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/like")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @PostMapping("/{articleId}")
    public R<Boolean> like(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long articleId) {
        return R.ok(likeService.like(userId, articleId));
    }

    @DeleteMapping("/{articleId}")
    public R<Boolean> cancel(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long articleId) {
        return R.ok(likeService.cancel(userId, articleId));
    }
}
