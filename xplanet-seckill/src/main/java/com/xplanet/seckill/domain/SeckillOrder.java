package com.xplanet.seckill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("seckill_order")
public class SeckillOrder {
    @TableId private Long id;
    private String eventId;
    private Long activityId;
    private Long skuId;
    private Long userId;
    private String status;
    private LocalDateTime createTime;
}
