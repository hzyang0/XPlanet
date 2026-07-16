package com.xplanet.article.outbox;

import lombok.Data;

@Data
public class ArticleChangeOutboxEvent {
    private Long id;
    private String eventId;
    private Long articleId;
    private String operation;
    private Integer retryCount;
}
