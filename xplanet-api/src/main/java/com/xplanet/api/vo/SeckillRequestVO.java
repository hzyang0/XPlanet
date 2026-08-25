package com.xplanet.api.vo;

import lombok.Data;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;

@Data
public class SeckillRequestVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long requestId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;
    private String status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
    private String failReason;
    private LocalDateTime createTime;
}
