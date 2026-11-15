package com.alighazi.gateway.ops;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Durable store for operator-managed routes. Built-in routes remain in application configuration.
 */
public interface RouteStore {

    Mono<List<RouteConfig>> load();

    Mono<Void> save(RouteConfig route);

    Mono<Void> delete(String serviceId);

    record RouteConfig(
            String id,
            String backend,
            String scope,
            boolean enabled,
            int replenishPerSecond,
            int burst) {
    }
}
