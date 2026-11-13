package com.alighazi.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
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
                    .filter(name -> name != null && !name.isBlank())
                    .switchIfEmpty(Mono.fromSupplier(() -> subjectFromBearer(
                            exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))))
                    .doOnNext(caller -> governance.record(serviceId, code, disabled, caller))
                    .onErrorResume(ex -> {
                        governance.record(serviceId, code, disabled, "anonymous");
                        return Mono.empty();
                    })
                    .then();
        });
        return chain.filter(exchange);
    }

    static String subjectFromBearer(String authorization) {
        if (authorization == null) {
            return "anonymous";
        }
        String value = authorization.trim();
        if (value.length() < 8 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "anonymous";
        }
        String token = value.substring(7).trim();
        int first = token.indexOf('.');
        int second = first < 0 ? -1 : token.indexOf('.', first + 1);
        if (first < 1 || second < 0) {
            return "anonymous";
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token.substring(first + 1, second));
            String json = new String(decoded, StandardCharsets.UTF_8);
            int key = json.indexOf("\"sub\"");
            if (key < 0) {
                return "anonymous";
            }
            int colon = json.indexOf(':', key + 5);
            int quote = colon < 0 ? -1 : json.indexOf('"', colon + 1);
            int end = quote < 0 ? -1 : json.indexOf('"', quote + 1);
            if (quote < 0 || end <= quote) {
                return "anonymous";
            }
            String subject = json.substring(quote + 1, end).trim();
            if (subject.isEmpty() || subject.length() > 80) {
                return "anonymous";
            }
            return subject;
        } catch (IllegalArgumentException ex) {
            return "anonymous";
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
