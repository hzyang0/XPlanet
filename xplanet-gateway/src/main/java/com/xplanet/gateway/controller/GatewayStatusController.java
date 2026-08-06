package com.xplanet.gateway.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class GatewayStatusController {

    private final String frontendUrl;

    public GatewayStatusController(
            @Value("${xplanet.frontend-url:http://127.0.0.1:4173}") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/")
    public Map<String, Object> status() {
        return Map.of(
                "service", "xplanet-gateway",
                "status", "UP",
                "message", "XPlanet Gateway is running. Open the frontend workspace to use the system.",
                "frontend", frontendUrl,
                "health", "/actuator/health",
                "routes", List.of("/api/user/**", "/api/article/**", "/api/comment/**", "/api/like/**", "/api/ai/**")
        );
    }
}
