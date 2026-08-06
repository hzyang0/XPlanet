package com.xplanet.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Versioned command sent from the Java control plane to the Agent worker. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private String eventId;
    private String eventType;
    private Integer schemaVersion;
    private Long taskId;
    private String runId;
    private Integer aggregateVersion;
    private Long occurredAt;
    private String traceId;
    private Long userId;
    private String question;
    private String provider;
    private Integer maxSources;
    private Integer maxToolCalls;
    private Integer maxTokens;
    private Integer deadlineSeconds;
}
