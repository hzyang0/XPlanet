package com.xplanet.api.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticlePublishResultVO {
    private Long articleId;
    private boolean created;
}
