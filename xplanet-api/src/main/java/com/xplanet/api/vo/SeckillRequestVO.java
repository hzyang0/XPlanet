package com.xplanet.api.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SeckillRequestVO {
    private Long requestId;
    private Long activityId;
    private Long skuId;
    private String status;
    private Long orderId;
    private String failReason;
    private LocalDateTime createTime;
}
