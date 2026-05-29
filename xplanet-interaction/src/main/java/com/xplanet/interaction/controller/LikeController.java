package com.xplanet.interaction.controller;

import com.xplanet.common.auth.UserContext;
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
    public R<Boolean> like(@PathVariable Long articleId) {
        return R.ok(likeService.like(UserContext.getUserId(), articleId));
    }

    @DeleteMapping("/{articleId}")
    public R<Boolean> cancel(@PathVariable Long articleId) {
        return R.ok(likeService.cancel(UserContext.getUserId(), articleId));
    }
}
