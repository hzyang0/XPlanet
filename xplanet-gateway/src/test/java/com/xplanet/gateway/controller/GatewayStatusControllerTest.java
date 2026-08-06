package com.xplanet.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class GatewayStatusControllerTest {

    @Test
    void rootDescribesTheGatewayInsteadOfReturningWhitelabel404() {
        WebTestClient.bindToController(new GatewayStatusController("http://127.0.0.1:4173"))
                .build()
                .get()
                .uri("/")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("xplanet-gateway")
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.frontend").isEqualTo("http://127.0.0.1:4173")
                .jsonPath("$.health").isEqualTo("/actuator/health");
    }
}
