package com.xplanet.ai.service;

import com.xplanet.ai.client.AgentServiceClient;
import com.xplanet.api.dto.AiResearchResult;
import com.xplanet.api.dto.AiTaskCommand;
import feign.FeignException;
import feign.Request;
import feign.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskExecutionServiceTest {

    private AgentServiceClient client;
    private AiTaskStateService stateService;
    private AiResultPersistenceService resultService;
    private AgentTaskExecutionService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        client = mock(AgentServiceClient.class);
        stateService = mock(AiTaskStateService.class);
        resultService = mock(AiResultPersistenceService.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new AgentTaskExecutionService(client, new InternalTokenVerifier("x".repeat(32)),
                stateService, resultService, meterRegistry);
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
        org.assertj.core.api.Assertions.assertThat(meterRegistry.counter(
                "xplanet.ai.agent.executions", "outcome", "success").count()).isEqualTo(1);
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
    void shouldFailAndAcknowledgeNonRetryableAgentRejection() {
        AiTaskCommand command = command("AI_TASK_REQUESTED");
        Request request = Request.create(Request.HttpMethod.POST, "http://agent/internal/tasks/execute",
                Map.of(), null, StandardCharsets.UTF_8);
        FeignException rejection = FeignException.errorStatus("AgentServiceClient#execute",
                Response.builder().status(422).reason("invalid action").headers(Map.of())
                        .request(request).build());
        when(stateService.begin(1L, "run-1")).thenReturn(true);
        when(client.execute("x".repeat(32), command)).thenThrow(rejection);

        service.handle(command);

        verify(stateService).fail(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("run-1"), anyString());
        verify(stateService, never()).retry(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("run-1"), anyString());
        verify(resultService).acknowledge("event-1");
        org.assertj.core.api.Assertions.assertThat(meterRegistry.counter(
                "xplanet.ai.agent.executions", "outcome", "rejected").count()).isEqualTo(1);
    }

    @Test
    void shouldFailWithoutMqRetryWhenReturnedResultViolatesJavaContract() {
        AiTaskCommand command = command("AI_TASK_REQUESTED");
        AiResearchResult result = new AiResearchResult();
        when(stateService.begin(1L, "run-1")).thenReturn(true);
        when(client.execute("x".repeat(32), command)).thenReturn(result);
        doThrow(new IllegalArgumentException("invalid citation binding"))
                .when(resultService).complete("event-1", result);

        service.handle(command);

        verify(stateService).fail(1L, "run-1", "invalid citation binding");
        verify(stateService, never()).retry(1L, "run-1", "invalid citation binding");
        verify(resultService).acknowledge("event-1");
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
