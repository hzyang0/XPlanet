package com.xplanet.ai.service;

import com.xplanet.ai.client.AgentServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exposes provider availability without ever returning the configured API key. */
@Service
@RequiredArgsConstructor
public class AiProviderService {

    private final AgentServiceClient agentClient;

    public Map<String, Object> capabilities() {
        try {
            return agentClient.health();
        } catch (Exception exception) {
            Map<String, Object> unavailable = new LinkedHashMap<>();
            unavailable.put("status", "DOWN");
            unavailable.put("defaultProvider", "offline-demo");
            unavailable.put("providers", Map.of(
                    "offline-demo", false,
                    "deepseek-tools", false));
            unavailable.put("message", "Agent 服务暂时不可用");
            return unavailable;
        }
    }
}
