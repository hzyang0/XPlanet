package com.xplanet.ai.service;

import com.xplanet.ai.domain.AiTaskStatus;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiTaskStateService {

    private final AiTaskMapper taskMapper;

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
        String abbreviated = error == null ? "agent execution failed"
                : error.substring(0, Math.min(1000, error.length()));
        taskMapper.markRetrying(taskId, runId, abbreviated);
        taskMapper.markRunRetrying(taskId, runId, abbreviated);
    }
}
