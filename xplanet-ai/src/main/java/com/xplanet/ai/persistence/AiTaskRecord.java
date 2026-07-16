package com.xplanet.ai.persistence;

import com.xplanet.api.vo.AiTaskVO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiTaskRecord {
    private Long id;
    private Long userId;
    private String idempotencyKey;
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

    public AiTaskVO toView() {
        AiTaskVO view = new AiTaskVO();
        view.setId(id);
        view.setUserId(userId);
        view.setQuestion(question);
        view.setStatus(status);
        view.setCurrentRunId(currentRunId);
        view.setVersion(version);
        view.setMaxSources(maxSources);
        view.setMaxToolCalls(maxToolCalls);
        view.setMaxTokens(maxTokens);
        view.setDeadlineSeconds(deadlineSeconds);
        view.setLastError(lastError);
        view.setCreateTime(createTime);
        view.setUpdateTime(updateTime);
        return view;
    }
}
