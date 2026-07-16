package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiRunStepMapper;
import com.xplanet.ai.persistence.AiRunStepRecord;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import com.xplanet.api.dto.AiCheckpointData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCheckpointServiceTest {

    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private AiRunStepMapper runStepMapper;

    private AiCheckpointService service;

    @BeforeEach
    void setUp() {
        service = new AiCheckpointService(taskMapper, runStepMapper, new SimpleMeterRegistry());
    }

    @Test
    void savesCheckpointOnlyForCurrentNonTerminalRun() {
        AiTaskRecord task = task("run-1", "RUNNING");
        AiCheckpointData checkpoint = checkpoint("run-1");
        when(taskMapper.findInternalForUpdate(9L)).thenReturn(task);

        AiCheckpointData saved = service.save(9L, checkpoint);

        assertEquals(checkpoint, saved);
        verify(runStepMapper).upsert(checkpoint);
        verify(runStepMapper).updateCurrentNode(9L, "run-1", "PLANNER");
    }

    @Test
    void rejectsCheckpointAfterCancellation() {
        when(taskMapper.findInternalForUpdate(9L)).thenReturn(task("run-1", "CANCELLED"));

        assertThrows(IllegalStateException.class, () -> service.save(9L, checkpoint("run-1")));
    }

    @Test
    void loadsLatestCheckpointAndRejectsForeignRun() {
        when(taskMapper.findInternal(9L)).thenReturn(task("run-1", "RETRYING"));
        AiRunStepRecord record = new AiRunStepRecord();
        record.setRunId("run-1");
        record.setNodeName("PLANNER");
        record.setInputHash("a".repeat(64));
        record.setStateVersion(1);
        record.setCheckpointJson("{}");
        record.setDurationMs(12L);
        when(runStepMapper.findLatest("run-1")).thenReturn(record);

        AiCheckpointData loaded = service.load(9L, "run-1");

        assertNotNull(loaded);
        assertEquals("PLANNER", loaded.getNode());
        assertThrows(IllegalArgumentException.class, () -> service.load(9L, "run-2"));
    }

    private AiTaskRecord task(String runId, String status) {
        AiTaskRecord task = new AiTaskRecord();
        task.setCurrentRunId(runId);
        task.setStatus(status);
        return task;
    }

    private AiCheckpointData checkpoint(String runId) {
        return AiCheckpointData.builder()
                .runId(runId)
                .node("PLANNER")
                .inputHash("a".repeat(64))
                .stateVersion(1)
                .stateJson("{}")
                .durationMs(12L)
                .build();
    }
}
