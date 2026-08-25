package com.xplanet.api.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SeckillSubmitVO {
    private Long requestId;
    /** QUEUED means accepted by Redis and waiting for the order consumer. */
    private String status;
    private String message;
}
