package com.xplanet.ai.service;

import com.xplanet.ai.domain.AiTaskStatus;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiTaskStateService {

    private final AiTaskMapper taskMapper;

    @Value("${ai.agent.max-attempts:3}")
    private int maxAttempts;

    @Transactional
    public boolean begin(Long taskId, String runId) {
        AiTaskRecord task = taskMapper.findInternal(taskId);
        if (task == null || !runId.equals(task.getCurrentRunId())) {
            return false;
        }
        AiTaskStatus status = AiTaskStatus.valueOf(task.getStatus());
        if (status == AiTaskStatus.CANCELLED || status == AiTaskStatus.SUCCEEDED
                || status == AiTaskStatus.FAILED || status == AiTaskStatus.WAITING_REVIEW) {
            return false;
        }
        if (status != AiTaskStatus.RUNNING && taskMapper.markRunning(taskId, runId) != 1) {
            throw new IllegalStateException("AI task state changed while claiming run");
        }
        if (taskMapper.markRunRunning(taskId, runId) != 1) {
            throw new IllegalStateException("failed to mark AI run running");
        }
        return true;
    }

    @Transactional
    public void retry(Long taskId, String runId, String error) {
        AiTaskRecord task = taskMapper.findInternal(taskId);
        if (task == null || AiTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return;
        }
        String abbreviated = abbreviate(error);
        Integer attempt = taskMapper.findRunAttempt(taskId, runId);
        if (attempt != null && attempt >= maxAttempts) {
            taskMapper.markFailed(taskId, runId, abbreviated);
            taskMapper.markRunFailed(taskId, runId, abbreviated);
            return;
        }
        taskMapper.markRetrying(taskId, runId, abbreviated);
        taskMapper.markRunRetrying(taskId, runId, abbreviated);
    }

    @Transactional
    public void fail(Long taskId, String runId, String error) {
        AiTaskRecord task = taskMapper.findInternal(taskId);
        if (task == null || AiTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return;
        }
        String abbreviated = abbreviate(error);
        taskMapper.markFailed(taskId, runId, abbreviated);
        taskMapper.markRunFailed(taskId, runId, abbreviated);
    }

    private String abbreviate(String error) {
        return error == null ? "agent execution failed"
                : error.substring(0, Math.min(1000, error.length()));
    }
}
