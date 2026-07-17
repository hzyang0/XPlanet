package com.xplanet.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.token.secret=gateway-context-secret-with-at-least-32-bytes")
class GatewayRoutesTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void shouldLoadAllPublicServiceRoutes() {
        Set<String> routeIds = routeDefinitionLocator.getRouteDefinitions()
                .map(definition -> definition.getId())
                .collectList()
                .map(Set::copyOf)
                .block();

        assertThat(routeIds).containsExactlyInAnyOrder(
                "user-service", "article-service", "interaction-service", "ai-service");
    }
}
