package com.alighazi.gateway.config;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;

import com.alighazi.gateway.ops.RouteGovernance;
import com.alighazi.gateway.ops.RouteGovernance.ServiceOperations;

import reactor.core.publisher.Mono;

@Component
public class RegisteredServiceAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext> {

    private final RouteGovernance governance;

    public RegisteredServiceAuthorizationManager(RouteGovernance governance) {
        this.governance = governance;
    }

    @Override
    public Mono<AuthorizationDecision> check(Mono<Authentication> authentication, AuthorizationContext context) {
        String serviceId = governance.resolve(context.getExchange().getRequest().getPath().value());
        ServiceOperations operation = serviceId == null ? null : governance.operations().stream()
                .filter(candidate -> candidate.id().equals(serviceId) && !candidate.system())
                .findFirst()
                .orElse(null);
        if (operation == null) {
            return Mono.just(new AuthorizationDecision(false));
        }
        String authority = "SCOPE_" + operation.scope();
        return authentication
                .filter(Authentication::isAuthenticated)
                .map(auth -> new AuthorizationDecision(auth.getAuthorities().stream()
                        .anyMatch(granted -> authority.equals(granted.getAuthority()))))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }
}
