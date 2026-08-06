package com.xplanet.ai.client;

import com.xplanet.api.dto.AiResearchResult;
import com.xplanet.api.dto.AiTaskCommand;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@FeignClient(name = "agent-service", url = "${agent-service.base-url:http://localhost:8000}")
public interface AgentServiceClient {

    @GetMapping("/health")
    Map<String, Object> health();

    @PostMapping("/internal/tasks/execute")
    AiResearchResult execute(@RequestHeader("X-Agent-Token") String internalToken,
                             @RequestBody AiTaskCommand command);
}
