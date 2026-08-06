package com.xplanet.ai.service;

import com.xplanet.ai.outbox.AiOutboxMapper;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import com.xplanet.api.dto.AiTaskCommand;
import com.xplanet.api.request.CreateResearchTaskRequest;
import com.xplanet.api.vo.AiTaskVO;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskServiceTest {

    private AiTaskMapper taskMapper;
    private AiOutboxMapper outboxMapper;
    private AiTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AiTaskMapper.class);
        outboxMapper = mock(AiOutboxMapper.class);
        service = new AiTaskService(taskMapper, outboxMapper);
    }

    @Test
    void shouldPersistTaskRunAndOutboxAtomically() {
        doAnswer(invocation -> {
            AiTaskRecord task = invocation.getArgument(0);
            task.setId(101L);
            return 1;
        }).when(taskMapper).insertTask(any(AiTaskRecord.class));
        when(taskMapper.insertRun(anyString(), eq(101L))).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(101L), anyString(),
                eq("AI_TASK_REQUESTED"), eq(0), anyString())).thenReturn(1);

        AiTaskVO result = service.create(7L, " request-1 ", request("How does Outbox work?"));

        assertThat(result.getId()).isEqualTo(101L);
        assertThat(result.getStatus()).isEqualTo("QUEUED");
        assertThat(result.getVersion()).isZero();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insertEvent(anyString(), eq(101L), eq(result.getCurrentRunId()),
                eq("AI_TASK_REQUESTED"), eq(0), payload.capture());
        AiTaskCommand command = JsonUtil.fromJson(payload.getValue(), AiTaskCommand.class);
        assertThat(command.getTaskId()).isEqualTo(101L);
        assertThat(command.getUserId()).isEqualTo(7L);
        assertThat(command.getProvider()).isEqualTo("offline-demo");
        assertThat(command.getMaxToolCalls()).isEqualTo(10);
    }

    @Test
    void shouldReturnExistingTaskForSameIdempotentRequest() {
        AiTaskRecord existing = task(9L, "QUEUED", 0);
        when(taskMapper.findByIdempotencyKey(7L, "same-key")).thenReturn(existing);

        AiTaskVO result = service.create(7L, "same-key", request(existing.getQuestion()));

        assertThat(result.getId()).isEqualTo(9L);
        verify(taskMapper, never()).insertTask(any());
        verify(outboxMapper, never()).insertEvent(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void shouldRejectReusingKeyForDifferentPayload() {
        AiTaskRecord existing = task(9L, "QUEUED", 0);
        when(taskMapper.findByIdempotencyKey(7L, "same-key")).thenReturn(existing);

        assertThatThrownBy(() -> service.create(7L, "same-key", request("different question")))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.AI_IDEMPOTENCY_CONFLICT.getCode());
    }

    @Test
    void shouldTreatProviderAsPartOfTheIdempotentRequest() {
        AiTaskRecord existing = task(9L, "QUEUED", 0);
        when(taskMapper.findByIdempotencyKey(7L, "same-key")).thenReturn(existing);
        CreateResearchTaskRequest online = request(existing.getQuestion());
        online.setProvider("deepseek-tools");

        assertThatThrownBy(() -> service.create(7L, "same-key", online))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.AI_IDEMPOTENCY_CONFLICT.getCode());
    }

    @Test
    void shouldResolveConcurrentIdempotentInsertWithoutDuplicateRun() {
        AiTaskRecord existing = task(11L, "QUEUED", 0);
        when(taskMapper.findByIdempotencyKey(7L, "race-key"))
                .thenReturn(null, existing);
        when(taskMapper.insertTask(any())).thenReturn(0);

        assertThat(service.create(7L, "race-key", request(existing.getQuestion())).getId()).isEqualTo(11L);

        verify(taskMapper, never()).insertRun(anyString(), anyLong());
        verify(outboxMapper, never()).insertEvent(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void shouldCancelActiveTaskAndAppendCancelCommand() {
        AiTaskRecord active = task(12L, "RUNNING", 3);
        when(taskMapper.findOwned(12L, 7L)).thenReturn(active);
        when(taskMapper.cancel(12L, 7L, 3)).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(12L), eq(active.getCurrentRunId()),
                eq("AI_TASK_CANCELLED"), eq(4), anyString())).thenReturn(1);

        AiTaskVO cancelled = service.cancel(7L, 12L);

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getVersion()).isEqualTo(4);
    }

    @Test
    void shouldTreatRepeatedCancelAsIdempotent() {
        AiTaskRecord cancelled = task(12L, "CANCELLED", 4);
        when(taskMapper.findOwned(12L, 7L)).thenReturn(cancelled);

        assertThat(service.cancel(7L, 12L).getStatus()).isEqualTo("CANCELLED");

        verify(taskMapper, never()).cancel(anyLong(), anyLong(), anyInt());
        verify(outboxMapper, never()).insertEvent(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void shouldRejectCancelAfterSuccess() {
        when(taskMapper.findOwned(12L, 7L)).thenReturn(task(12L, "SUCCEEDED", 5));

        assertThatThrownBy(() -> service.cancel(7L, 12L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.AI_TASK_STATE_CONFLICT.getCode());
    }

    @Test
    void shouldHideTasksOwnedByAnotherUser() {
        when(taskMapper.findOwned(99L, 7L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(7L, 99L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.AI_TASK_NOT_FOUND.getCode());
    }

    @Test
    void shouldBoundListSize() {
        when(taskMapper.listOwned(7L, 100)).thenReturn(List.of(task(1L, "QUEUED", 0)));

        assertThat(service.list(7L, 1000)).hasSize(1);
        verify(taskMapper).listOwned(7L, 100);
    }

    @Test
    void shouldEnforceBudgetsInsideServiceBoundary() {
        CreateResearchTaskRequest invalid = request("question");
        invalid.setMaxToolCalls(51);

        assertThatThrownBy(() -> service.create(7L, "budget-key", invalid))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());

        verify(taskMapper, never()).insertTask(any());
    }

    private CreateResearchTaskRequest request(String question) {
        CreateResearchTaskRequest request = new CreateResearchTaskRequest();
        request.setQuestion(question);
        return request;
    }

    private AiTaskRecord task(Long id, String status, int version) {
        AiTaskRecord task = new AiTaskRecord();
        task.setId(id);
        task.setUserId(7L);
        task.setIdempotencyKey("same-key");
        task.setQuestion("How does Outbox work?");
        task.setProvider("offline-demo");
        task.setStatus(status);
        task.setCurrentRunId("run-1");
        task.setVersion(version);
        task.setMaxSources(5);
        task.setMaxToolCalls(10);
        task.setMaxTokens(8000);
        task.setDeadlineSeconds(300);
        return task;
    }
}
