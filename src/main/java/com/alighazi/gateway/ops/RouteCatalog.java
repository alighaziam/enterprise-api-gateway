package com.alighazi.gateway.ops;

import org.springframework.stereotype.Service;

import com.alighazi.gateway.ops.RouteGovernance.ServiceOperations;

import reactor.core.publisher.Mono;

@Service
public class RouteCatalog {

    private final RouteGovernance governance;
    private final GatewayRoutePublisher routes;

    public RouteCatalog(RouteGovernance governance, GatewayRoutePublisher routes) {
        this.governance = governance;
        this.routes = routes;
    }

    public Mono<ServiceOperations> create(
            String id, String backend, String scope, int replenish, int burst, boolean enabled) {
        ServiceOperations created;
        try {
            created = governance.create(id, backend, scope, replenish, burst, enabled);
        } catch (RuntimeException ex) {
            return Mono.error(ex);
        }
        return routes.publish(created)
                .thenReturn(created)
                .onErrorResume(ex -> {
                    governance.delete(created.id());
                    return Mono.error(ex);
                });
    }

    public Mono<ServiceOperations> update(
            String id, Boolean enabled, Integer replenish, Integer burst, String backend, String scope) {
        ServiceOperations updated;
        try {
            updated = governance.update(id, enabled, replenish, burst, backend, scope);
        } catch (RuntimeException ex) {
            return Mono.error(ex);
        }
        if (updated.system()) {
            return Mono.just(updated);
        }
        return routes.publish(updated).thenReturn(updated);
    }

    public Mono<Void> delete(String id) {
        try {
            governance.operations().stream()
                    .filter(operation -> operation.id().equals(id))
                    .findFirst()
                    .filter(operation -> !operation.system())
                    .orElseThrow(() -> governance.operations().stream().anyMatch(operation -> operation.id().equals(id))
                            ? new IllegalArgumentException("system_service")
                            : new RouteGovernance.UnknownService());
        } catch (RuntimeException ex) {
            return Mono.error(ex);
        }
        return routes.unpublish(id).then(Mono.fromRunnable(() -> governance.delete(id)));
    }
}
