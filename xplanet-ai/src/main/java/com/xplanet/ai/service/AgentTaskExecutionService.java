package com.xplanet.ai.service;

import com.xplanet.ai.client.AgentServiceClient;
import com.xplanet.api.dto.AiResearchResult;
import com.xplanet.api.dto.AiTaskCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentTaskExecutionService {

    private final AgentServiceClient agentClient;
    private final InternalTokenVerifier internalToken;
    private final AiTaskStateService stateService;
    private final AiResultPersistenceService resultService;

    public void handle(AiTaskCommand command) {
        if (command == null || command.getEventId() == null || command.getTaskId() == null
                || command.getRunId() == null) {
            throw new IllegalArgumentException("invalid AI task command");
        }
        if (!"AI_TASK_REQUESTED".equals(command.getEventType())) {
            resultService.acknowledge(command.getEventId());
            return;
        }
        if (!stateService.begin(command.getTaskId(), command.getRunId())) {
            resultService.acknowledge(command.getEventId());
            return;
        }
        try {
            AiResearchResult result = agentClient.execute(internalToken.value(), command);
            resultService.complete(command.getEventId(), result);
        } catch (Exception e) {
            stateService.retry(command.getTaskId(), command.getRunId(), e.getMessage());
            throw e;
        }
    }
}
