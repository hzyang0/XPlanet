package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiRunStepMapper;
import com.xplanet.ai.persistence.AiRunStepRecord;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import com.xplanet.api.dto.AiCheckpointData;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AiCheckpointService {

    private final AiTaskMapper taskMapper;
    private final AiRunStepMapper runStepMapper;
    private final MeterRegistry meterRegistry;

    public AiCheckpointData load(Long taskId, String runId) {
        AiTaskRecord task = taskMapper.findInternal(taskId);
        requireCurrentRun(task, runId);
        AiRunStepRecord latest = runStepMapper.findLatest(runId);
        return latest == null ? null : latest.toCheckpoint();
    }

    @Transactional
    public AiCheckpointData save(Long taskId, AiCheckpointData checkpoint) {
        AiTaskRecord task = taskMapper.findInternalForUpdate(taskId);
        requireCurrentRun(task, checkpoint.getRunId());
        if ("CANCELLED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())
                || "SUCCEEDED".equals(task.getStatus())) {
            throw new IllegalStateException("cannot checkpoint a terminal AI task");
        }
        runStepMapper.upsert(checkpoint);
        runStepMapper.updateCurrentNode(taskId, checkpoint.getRunId(), checkpoint.getNode());
        meterRegistry.counter("xplanet.ai.agent.checkpoints", "node", checkpoint.getNode()).increment();
        meterRegistry.timer("xplanet.ai.agent.node.duration", "node", checkpoint.getNode())
                .record(Duration.ofMillis(checkpoint.getDurationMs() == null ? 0 : checkpoint.getDurationMs()));
        return checkpoint;
    }

    private void requireCurrentRun(AiTaskRecord task, String runId) {
        if (task == null || runId == null || !runId.equals(task.getCurrentRunId())) {
            throw new IllegalArgumentException("checkpoint does not match current task run");
        }
    }
}
