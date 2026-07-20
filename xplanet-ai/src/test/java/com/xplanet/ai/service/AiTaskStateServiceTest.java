package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskStateServiceTest {

    @Mock
    private AiTaskMapper taskMapper;

    private AiTaskStateService service;

    @BeforeEach
    void setUp() {
        service = new AiTaskStateService(taskMapper);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
    }

    @Test
    void marksRetryableFailureBeforeAttemptLimit() {
        when(taskMapper.findInternal(1L)).thenReturn(task("RUNNING"));
        when(taskMapper.findRunAttempt(1L, "run-1")).thenReturn(2);

        service.retry(1L, "run-1", "temporary failure");

        verify(taskMapper).markRetrying(1L, "run-1", "temporary failure");
        verify(taskMapper).markRunRetrying(1L, "run-1", "temporary failure");
        verify(taskMapper, never()).markFailed(1L, "run-1", "temporary failure");
    }

    @Test
    void movesTaskAndRunToFailedAtAttemptLimit() {
        when(taskMapper.findInternal(1L)).thenReturn(task("RUNNING"));
        when(taskMapper.findRunAttempt(1L, "run-1")).thenReturn(3);

        service.retry(1L, "run-1", "permanent failure");

        verify(taskMapper).markFailed(1L, "run-1", "permanent failure");
        verify(taskMapper).markRunFailed(1L, "run-1", "permanent failure");
        verify(taskMapper, never()).markRetrying(1L, "run-1", "permanent failure");
    }

    @Test
    void immediatelyFailsNonRetryableAgentError() {
        when(taskMapper.findInternal(1L)).thenReturn(task("RUNNING"));

        service.fail(1L, "run-1", "invalid tool action");

        verify(taskMapper).markFailed(1L, "run-1", "invalid tool action");
        verify(taskMapper).markRunFailed(1L, "run-1", "invalid tool action");
        verify(taskMapper, never()).findRunAttempt(1L, "run-1");
    }

    private AiTaskRecord task(String status) {
        AiTaskRecord task = new AiTaskRecord();
        task.setStatus(status);
        task.setCurrentRunId("run-1");
        return task;
    }
}
