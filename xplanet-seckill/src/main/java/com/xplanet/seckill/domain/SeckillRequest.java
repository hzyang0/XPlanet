package com.xplanet.seckill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("seckill_request")
public class SeckillRequest {
    @TableId private Long id;
    private String requestNo;
    private Long activityId;
    private Long skuId;
    private Long userId;
    private String status;
    private Long orderId;
    private String failReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
