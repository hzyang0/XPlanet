package com.xplanet.api.vo;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评论 VO,支持两级嵌套(顶级评论 + 其下的回复)。
 */
@Data
public class CommentVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long articleId;
    private Long userId;
    private String userName;
    private Long parentId;
    private String content;
    private LocalDateTime createTime;
    /** 子回复(只在顶级评论上有值) */
    private List<CommentVO> children = new ArrayList<>();
}
