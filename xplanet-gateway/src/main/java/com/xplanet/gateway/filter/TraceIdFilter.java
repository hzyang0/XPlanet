package com.xplanet.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关注入 traceId,贯穿下游服务。
 * 下游通过 RequestHeader 拿到 X-Trace-Id 写入 MDC,日志查问题就能串起来。
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(HEADER, traceId).build();
        exchange.getResponse().getHeaders().add(HEADER, traceId);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
