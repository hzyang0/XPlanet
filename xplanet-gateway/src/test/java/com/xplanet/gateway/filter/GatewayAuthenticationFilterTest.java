package com.xplanet.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xplanet.common.auth.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAuthenticationFilterTest {

    private GatewayAuthenticationFilter filter;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("gateway-test-secret-with-at-least-32-bytes", 3600, "xplanet");
        filter = new GatewayAuthenticationFilter(tokenService, new ObjectMapper());
    }

    @Test
    void shouldAllowLoginAndPublicArticleRead() {
        assertForwarded(MockServerHttpRequest.post("/api/user/login").build());
        assertForwarded(MockServerHttpRequest.get("/api/article/1").build());
        assertForwarded(MockServerHttpRequest.get("/actuator").build());
        assertForwarded(MockServerHttpRequest.get("/favicon.ico").build());
    }

    @Test
    void shouldRejectProtectedApiWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/like/1"));
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, current -> {
            forwarded.set(true);
            return current.getResponse().setComplete();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldForwardProtectedApiWithValidBearerToken() {
        String token = tokenService.issue(7L);
        assertForwarded(MockServerHttpRequest.post("/api/article")
                .header("Authorization", "Bearer " + token)
                .build());
    }

    private void assertForwarded(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(exchange, current -> {
            forwarded.set(true);
            return current.getResponse().setComplete();
        }).block();
        assertThat(forwarded).isTrue();
    }
}
