package com.xplanet.interaction.persistence;

import lombok.Data;

@Data
public class LikeOutboxEvent {
    private Long id;
    private String eventId;
    private Long userId;
    private Long articleId;
    private Integer delta;
    private Integer retryCount;
}
