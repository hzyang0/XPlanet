package com.xplanet.api.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.io.Serializable;

/**
 * 点赞消息。
 * <p>消息中携带 actionId(雪花/UUID)作为消费幂等键。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LikeMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 业务唯一 ID,用于消费端幂等 */
    private String actionId;

    private Long userId;
    private Long articleId;

    /** 1 = 点赞, -1 = 取消 */
    private int delta;

    /** 产生时间(毫秒) */
    private long timestamp;
}
