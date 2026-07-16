package com.xplanet.article.projection;

import lombok.Data;

@Data
public class LikeCountDelta {
    private Long id;
    private String eventId;
    private Long articleId;
    private Long delta;
}
