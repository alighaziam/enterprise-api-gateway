package com.alighazi.gateway.ops;

import java.net.URI;
import java.util.List;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.alighazi.gateway.ops.RouteGovernance.ServiceOperations;

import reactor.core.publisher.Mono;

@Component
public class GatewayRoutePublisher {

    private final RouteDefinitionWriter writer;
    private final ApplicationEventPublisher events;

    public GatewayRoutePublisher(RouteDefinitionWriter writer, ApplicationEventPublisher events) {
        this.writer = writer;
        this.events = events;
    }

    public Mono<Void> publish(ServiceOperations operation) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(RouteGovernance.routeId(operation.id()));
        definition.setUri(URI.create(operation.backend()));
        definition.setPredicates(List.of(new PredicateDefinition("Path=/api/" + operation.id() + "/**")));
        definition.setFilters(List.of(stripPrefix(), rateLimiter(operation)));
        return writer.save(Mono.just(definition))
                .doOnSuccess(ignored -> events.publishEvent(new RefreshRoutesEvent(this)));
    }

    public Mono<Void> unpublish(String serviceId) {
        return writer.delete(Mono.just(RouteGovernance.routeId(serviceId)))
                .doOnSuccess(ignored -> events.publishEvent(new RefreshRoutesEvent(this)));
    }

    private static FilterDefinition stripPrefix() {
        FilterDefinition filter = new FilterDefinition();
        filter.setName("StripPrefix");
        filter.addArg("parts", "2");
        return filter;
    }

    private static FilterDefinition rateLimiter(ServiceOperations operation) {
        FilterDefinition filter = new FilterDefinition();
        filter.setName("RequestRateLimiter");
        filter.addArg("redis-rate-limiter.replenishRate", Integer.toString(operation.replenishPerSecond()));
        filter.addArg("redis-rate-limiter.burstCapacity", Integer.toString(operation.burst()));
        filter.addArg("key-resolver", "#{@principalKeyResolver}");
        return filter;
    }
}
