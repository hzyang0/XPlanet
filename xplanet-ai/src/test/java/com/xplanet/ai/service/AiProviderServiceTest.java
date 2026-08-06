package com.xplanet.ai.service;

import com.xplanet.ai.client.AgentServiceClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiProviderServiceTest {

    @Test
    void shouldExposeCapabilitiesWithoutTransformingAgentResponse() {
        AgentServiceClient client = mock(AgentServiceClient.class);
        Map<String, Object> health = Map.of(
                "status", "UP",
                "providers", Map.of("offline-demo", true, "deepseek-tools", false));
        when(client.health()).thenReturn(health);

        assertThat(new AiProviderService(client).capabilities()).isEqualTo(health);
    }

    @Test
    void shouldReturnUnavailableCapabilitiesWhenAgentIsDown() {
        AgentServiceClient client = mock(AgentServiceClient.class);
        when(client.health()).thenThrow(new IllegalStateException("down"));

        Map<String, Object> result = new AiProviderService(client).capabilities();

        assertThat(result.get("status")).isEqualTo("DOWN");
        Map<?, ?> providers = (Map<?, ?>) result.get("providers");
        assertThat(providers.get("offline-demo")).isEqualTo(false);
        assertThat(providers.get("deepseek-tools")).isEqualTo(false);
    }
}
