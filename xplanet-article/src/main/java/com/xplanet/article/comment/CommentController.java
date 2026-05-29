package com.xplanet.article.comment;

import com.xplanet.api.request.CommentPublishRequest;
import com.xplanet.api.vo.CommentVO;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/comment")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /** 发布评论(或回复) */
    @PostMapping
    public R<Long> publish(@Valid @RequestBody CommentPublishRequest req) {
        return R.ok(commentService.publish(com.xplanet.common.auth.UserContext.getUserId(), req));
    }

    /** 查询某文章的评论(两级嵌套) */
    @GetMapping("/article/{articleId}")
    public R<List<CommentVO>> list(@PathVariable Long articleId) {
        return R.ok(commentService.listByArticle(articleId));
    }
}
