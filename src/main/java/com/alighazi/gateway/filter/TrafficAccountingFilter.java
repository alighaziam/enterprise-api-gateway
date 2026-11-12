package com.alighazi.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.alighazi.gateway.ops.RouteGovernance;

import reactor.core.publisher.Mono;

@Component
public class TrafficAccountingFilter implements WebFilter, Ordered {

    private final RouteGovernance governance;

    public TrafficAccountingFilter(RouteGovernance governance) {
        this.governance = governance;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String serviceId = RouteGovernance.serviceId(exchange.getRequest().getPath().value());
        if (serviceId == null) {
            return chain.filter(exchange);
        }
        exchange.getResponse().beforeCommit(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            boolean disabled = Boolean.TRUE.equals(exchange.getAttribute(RouteGovernance.DISABLED_ATTRIBUTE));
            governance.record(serviceId, status == null ? 0 : status.value(), disabled);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
