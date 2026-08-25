package com.xplanet.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SeckillSubmitVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long requestId;
    /** QUEUED means accepted by Redis and waiting for the order consumer. */
    private String status;
    private String message;
}
