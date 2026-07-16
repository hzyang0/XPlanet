package com.xplanet.ai.service;

import com.xplanet.ai.client.AgentServiceClient;
import com.xplanet.api.dto.AiResearchResult;
import com.xplanet.api.dto.AiTaskCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskExecutionServiceTest {

    private AgentServiceClient client;
    private AiTaskStateService stateService;
    private AiResultPersistenceService resultService;
    private AgentTaskExecutionService service;

    @BeforeEach
    void setUp() {
        client = mock(AgentServiceClient.class);
        stateService = mock(AiTaskStateService.class);
        resultService = mock(AiResultPersistenceService.class);
        service = new AgentTaskExecutionService(client, new InternalTokenVerifier("x".repeat(32)),
                stateService, resultService);
    }

    @Test
    void shouldExecuteAndPersistBeforeReturningToMqListener() {
        AiTaskCommand command = command("AI_TASK_REQUESTED");
        AiResearchResult result = new AiResearchResult();
        when(stateService.begin(1L, "run-1")).thenReturn(true);
        when(client.execute("x".repeat(32), command)).thenReturn(result);

        service.handle(command);

        verify(resultService).complete("event-1", result);
        verify(stateService, never()).retry(1L, "run-1", null);
    }

    @Test
    void shouldMarkRetryingAndRethrowAgentFailure() {
        AiTaskCommand command = command("AI_TASK_REQUESTED");
        when(stateService.begin(1L, "run-1")).thenReturn(true);
        when(client.execute("x".repeat(32), command)).thenThrow(new IllegalStateException("agent down"));

        assertThatThrownBy(() -> service.handle(command)).isInstanceOf(IllegalStateException.class);

        verify(stateService).retry(1L, "run-1", "agent down");
        verify(resultService, never()).complete("event-1", null);
    }

    @Test
    void shouldAcknowledgeCancelWithoutCallingAgent() {
        AiTaskCommand command = command("AI_TASK_CANCELLED");

        service.handle(command);

        verify(resultService).acknowledge("event-1");
        verify(client, never()).execute("x".repeat(32), command);
    }

    private AiTaskCommand command(String type) {
        return AiTaskCommand.builder().eventId("event-1").eventType(type)
                .taskId(1L).runId("run-1").build();
    }
}
