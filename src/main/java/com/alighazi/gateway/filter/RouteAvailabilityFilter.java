package com.alighazi.gateway.filter;

import java.nio.charset.StandardCharsets;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.alighazi.gateway.ops.RouteGovernance;

import reactor.core.publisher.Mono;

@Component
public class RouteAvailabilityFilter implements GlobalFilter, Ordered {

    private final RouteGovernance governance;

    public RouteAvailabilityFilter(RouteGovernance governance) {
        this.governance = governance;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String serviceId = RouteGovernance.serviceId(exchange.getRequest().getPath().value());
        if (serviceId == null || governance.isEnabled(serviceId)) {
            return chain.filter(exchange);
        }
        exchange.getAttributes().put(RouteGovernance.DISABLED_ATTRIBUTE, Boolean.TRUE);
        byte[] body = ("{\"error\":\"service_disabled\",\"service\":\"" + serviceId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
