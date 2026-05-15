package com.xplanet.api.vo;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章详情对外 VO。
 * <p>缓存中存的就是这个对象的 JSON,而不是 DO,避免数据库字段变更冲击缓存格式。
 */
@Data
public class ArticleDetailVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long authorId;
    private String authorName;
    private String title;
    private String content;
    private String tags;
    private Long likeCount;
    private Long viewCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
