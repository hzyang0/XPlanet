package com.xplanet.ai.service;

import com.xplanet.ai.client.AgentServiceClient;
import com.xplanet.api.dto.AiResearchResult;
import com.xplanet.api.dto.AiTaskCommand;
import feign.FeignException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AgentTaskExecutionService {

    private final AgentServiceClient agentClient;
    private final InternalTokenVerifier internalToken;
    private final AiTaskStateService stateService;
    private final AiResultPersistenceService resultService;
    private final MeterRegistry meterRegistry;

    public void handle(AiTaskCommand command) {
        if (command == null || command.getEventId() == null || command.getTaskId() == null
                || command.getRunId() == null) {
            throw new IllegalArgumentException("invalid AI task command");
        }
        if (!"AI_TASK_REQUESTED".equals(command.getEventType())) {
            resultService.acknowledge(command.getEventId());
            record("ignored", System.nanoTime());
            return;
        }
        long started = System.nanoTime();
        if (!stateService.begin(command.getTaskId(), command.getRunId())) {
            resultService.acknowledge(command.getEventId());
            record("skipped", started);
            return;
        }
        try {
            AiResearchResult result = agentClient.execute(internalToken.value(), command);
            resultService.complete(command.getEventId(), result);
            record("success", started);
        } catch (FeignException e) {
            if (e.status() == 400 || e.status() == 409 || e.status() == 422) {
                stateService.fail(command.getTaskId(), command.getRunId(), e.getMessage());
                resultService.acknowledge(command.getEventId());
                record("rejected", started);
                return;
            }
            stateService.retry(command.getTaskId(), command.getRunId(), e.getMessage());
            record("failure", started);
            throw e;
        } catch (IllegalArgumentException e) {
            stateService.fail(command.getTaskId(), command.getRunId(), e.getMessage());
            resultService.acknowledge(command.getEventId());
            record("rejected", started);
            return;
        } catch (Exception e) {
            stateService.retry(command.getTaskId(), command.getRunId(), e.getMessage());
            record("failure", started);
            throw e;
        }
    }

    private void record(String outcome, long started) {
        meterRegistry.counter("xplanet.ai.agent.executions", "outcome", outcome).increment();
        meterRegistry.timer("xplanet.ai.agent.duration", "outcome", outcome)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
    }
}
