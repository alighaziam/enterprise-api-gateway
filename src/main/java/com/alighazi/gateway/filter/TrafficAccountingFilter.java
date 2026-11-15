package com.alighazi.gateway.filter;

import java.security.Principal;

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
        String serviceId = governance.resolve(exchange.getRequest().getPath().value());
        if (serviceId == null) {
            return chain.filter(exchange);
        }
        exchange.getResponse().beforeCommit(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            int code = status == null ? 0 : status.value();
            boolean disabled = Boolean.TRUE.equals(exchange.getAttribute(RouteGovernance.DISABLED_ATTRIBUTE));
            return exchange.getPrincipal()
                    .map(Principal::getName)
                    .map(TrafficAccountingFilter::normalizePrincipal)
                    .defaultIfEmpty("anonymous")
                    .doOnNext(caller -> governance.record(serviceId, code, disabled, caller))
                    .onErrorResume(ex -> {
                        governance.record(serviceId, code, disabled, "anonymous");
                        return Mono.empty();
                    })
                    .then();
        });
        return chain.filter(exchange);
    }

    static String normalizePrincipal(String principal) {
        if (principal == null) {
            return "anonymous";
        }
        String value = principal.trim();
        return value.isEmpty() || value.length() > 80 ? "anonymous" : value;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
