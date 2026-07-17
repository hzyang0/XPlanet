package com.xplanet.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayTraceFilterTest {

    private final GatewayTraceFilter filter = new GatewayTraceFilter();

    @Test
    void shouldPreserveSafeTraceIdInRequestAndResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/article/1")
                        .header(GatewayTraceFilter.TRACE_HEADER, "trace-safe-1"));
        AtomicReference<String> forwarded = new AtomicReference<>();

        filter.filter(exchange, current -> {
            forwarded.set(current.getRequest().getHeaders().getFirst(GatewayTraceFilter.TRACE_HEADER));
            return current.getResponse().setComplete();
        }).block();

        assertThat(forwarded.get()).isEqualTo("trace-safe-1");
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayTraceFilter.TRACE_HEADER))
                .isEqualTo("trace-safe-1");
    }

    @Test
    void shouldReplaceUnsafeTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/article/1")
                        .header(GatewayTraceFilter.TRACE_HEADER, "unsafe trace id\r\n"));
        AtomicReference<String> forwarded = new AtomicReference<>();

        filter.filter(exchange, current -> {
            forwarded.set(current.getRequest().getHeaders().getFirst(GatewayTraceFilter.TRACE_HEADER));
            return current.getResponse().setComplete();
        }).block();

        assertThat(forwarded.get()).matches("[a-f0-9]{32}");
    }
}
