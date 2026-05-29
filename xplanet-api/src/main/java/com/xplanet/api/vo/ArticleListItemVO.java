package com.xplanet.api.vo;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章列表项 VO。
 * <p>列表不返回 content 全文,只返回摘要,减少传输量(列表页不需要正文)。
 */
@Data
public class ArticleListItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long authorId;
    private String authorName;
    private String title;
    private String summary;   // 内容摘要(取前 N 字)
    private String tags;
    private Long likeCount;
    private Long viewCount;
    private LocalDateTime createTime;
}
