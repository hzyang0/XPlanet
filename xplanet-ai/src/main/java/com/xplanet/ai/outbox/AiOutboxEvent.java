package com.xplanet.ai.outbox;

import lombok.Data;

@Data
public class AiOutboxEvent {
    private Long id;
    private String eventId;
    private String eventType;
    private String payload;
    private Integer retryCount;
}
