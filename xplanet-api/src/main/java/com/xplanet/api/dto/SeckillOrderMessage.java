package com.xplanet.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable command carried by RocketMQ. eventId is the consumer idempotency key. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage {
    private String eventId;
    private Long requestId;
    private Long activityId;
    private Long skuId;
    private Long userId;
}
