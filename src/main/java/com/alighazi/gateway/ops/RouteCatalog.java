package com.alighazi.gateway.ops;

import java.time.Duration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import com.alighazi.gateway.ops.RouteGovernance.ServiceOperations;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RouteCatalog implements ApplicationRunner {

    private final RouteGovernance governance;
    private final GatewayRoutePublisher routes;
    private final RouteStore store;

    public RouteCatalog(RouteGovernance governance, GatewayRoutePublisher routes, RouteStore store) {
        this.governance = governance;
        this.routes = routes;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        store.load()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::restoreOnStartup)
                .then()
                .block(Duration.ofSeconds(30));
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
                .then(store.save(toConfig(created)))
                .thenReturn(created)
                .onErrorResume(exception -> cleanupCreated(created, exception));
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
                .then(store.save(toConfig(updated)))
                .thenReturn(updated)
                .onErrorResume(exception -> restoreAfterFailure(previous, exception));
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
                .then(store.delete(id))
                .onErrorResume(exception -> restoreAfterFailure(previous, exception).then());
    }

    private Mono<Void> restoreOnStartup(RouteStore.RouteConfig config) {
        ServiceOperations restored = governance.create(
                config.id(), config.backend(), config.scope(),
                config.replenishPerSecond(), config.burst(), config.enabled());
        return routes.publish(restored)
                .onErrorResume(exception -> {
                    try {
                        governance.delete(restored.id());
                    } catch (RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                    return Mono.error(exception);
                });
    }

    private Mono<ServiceOperations> cleanupCreated(ServiceOperations created, RuntimeException failure) {
        return routes.unpublish(created.id())
                .onErrorResume(cleanupFailure -> {
                    failure.addSuppressed(cleanupFailure);
                    return Mono.empty();
                })
                .then(store.delete(created.id()).onErrorResume(cleanupFailure -> {
                    failure.addSuppressed(cleanupFailure);
                    return Mono.empty();
                }))
                .then(Mono.fromRunnable(() -> governance.delete(created.id()))
                        .onErrorResume(cleanupFailure -> {
                            failure.addSuppressed(cleanupFailure);
                            return Mono.empty();
                        }))
                .then(Mono.error(failure));
    }

    private Mono<ServiceOperations> restoreAfterFailure(ServiceOperations previous, RuntimeException failure) {
        ServiceOperations restored;
        try {
            try {
                governance.operation(previous.id());
                restored = governance.update(previous.id(), previous.enabled(),
                        previous.replenishPerSecond(), previous.burst(),
                        previous.backend(), previous.scope());
            } catch (RouteGovernance.UnknownService missing) {
                restored = governance.create(previous.id(), previous.backend(), previous.scope(),
                        previous.replenishPerSecond(), previous.burst(), previous.enabled());
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return Mono.error(failure);
        }

        return routes.publish(restored)
                .onErrorResume(rollbackFailure -> {
                    failure.addSuppressed(rollbackFailure);
                    return Mono.empty();
                })
                .then(store.save(toConfig(previous)).onErrorResume(rollbackFailure -> {
                    failure.addSuppressed(rollbackFailure);
                    return Mono.empty();
                }))
                .then(Mono.error(failure));
    }

    private static RouteStore.RouteConfig toConfig(ServiceOperations operation) {
        return new RouteStore.RouteConfig(
                operation.id(),
                operation.backend(),
                operation.scope(),
                operation.enabled(),
                operation.replenishPerSecond(),
                operation.burst());
    }
}
