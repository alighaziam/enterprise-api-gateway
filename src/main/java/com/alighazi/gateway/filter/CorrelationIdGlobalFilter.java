package com.alighazi.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_NAME = "X-Correlation-ID";
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incomingId = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);
        String correlationId = incomingId != null && SAFE_CORRELATION_ID.matcher(incomingId).matches()
                ? incomingId
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER_NAME, correlationId))
                .build();

        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}