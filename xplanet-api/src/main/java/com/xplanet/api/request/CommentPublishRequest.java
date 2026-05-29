package com.xplanet.api.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class CommentPublishRequest {
    @NotNull(message = "articleId 不能为空")
    private Long articleId;

    /** 父评论id,回复某条评论时传;顶级评论传 0 或不传 */
    private Long parentId = 0L;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容过长")
    private String content;
}
