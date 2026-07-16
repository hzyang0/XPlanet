package com.xplanet.api.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiTaskVO {
    private Long id;
    private Long userId;
    private String question;
    private String status;
    private String currentRunId;
    private Integer version;
    private Integer maxSources;
    private Integer maxToolCalls;
    private Integer maxTokens;
    private Integer deadlineSeconds;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
