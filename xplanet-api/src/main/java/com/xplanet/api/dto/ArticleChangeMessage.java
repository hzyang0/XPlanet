package com.xplanet.api.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.io.Serializable;

/**
 * 文章变更消息(给缓存清理消费者)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleChangeMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long articleId;
    /** UPDATE / DELETE / INSERT */
    private String op;
    private long timestamp;
}
