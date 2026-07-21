package com.xplanet.api.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InternalArticleSearchVO {
    private Long articleId;
    private String title;
    private String content;
    private Double score;
    private Long likeCount;
    private LocalDateTime updateTime;
}
