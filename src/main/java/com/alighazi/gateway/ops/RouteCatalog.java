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
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }

        return routes.publish(created)
                .thenReturn(created)
                .onErrorResume(exception -> routes.unpublish(created.id())
                        .onErrorResume(cleanupFailure -> {
                            exception.addSuppressed(cleanupFailure);
                            return Mono.empty();
                        })
                        .then(Mono.fromRunnable(() -> governance.delete(created.id()))
                                .onErrorResume(cleanupFailure -> {
                                    exception.addSuppressed(cleanupFailure);
                                    return Mono.empty();
                                }))
                        .then(Mono.error(exception)));
    }

    public Mono<ServiceOperations> update(
            String id, Boolean enabled, Integer replenish, Integer burst, String backend, String scope) {
        ServiceOperations previous;
        ServiceOperations updated;
        try {
            previous = governance.operation(id);
            updated = governance.update(id, enabled, replenish, burst, backend, scope);
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }

        if (updated.system()) {
            return Mono.just(updated);
        }

        return routes.publish(updated)
                .thenReturn(updated)
                .onErrorResume(exception -> {
                    ServiceOperations restored;
                    try {
                        restored = governance.update(id, previous.enabled(),
                                previous.replenishPerSecond(), previous.burst(),
                                previous.backend(), previous.scope());
                    } catch (RuntimeException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                        return Mono.error(exception);
                    }
                    return routes.publish(restored)
                            .onErrorResume(rollbackFailure -> {
                                exception.addSuppressed(rollbackFailure);
                                return Mono.empty();
                            })
                            .then(Mono.error(exception));
                });
    }

    public Mono<Void> delete(String id) {
        ServiceOperations previous;
        try {
            previous = governance.operation(id);
            if (previous.system()) {
                throw new IllegalArgumentException("system_service");
            }
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }

        return routes.unpublish(id)
                .then(Mono.fromRunnable(() -> governance.delete(id)))
                .onErrorResume(exception -> routes.publish(previous)
                        .onErrorResume(rollbackFailure -> {
                            exception.addSuppressed(rollbackFailure);
                            return Mono.empty();
                        })
                        .then(Mono.error(exception)));
    }
}
