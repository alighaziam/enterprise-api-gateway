package com.alighazi.gateway.ops;

import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

@Component
public class RedisRouteStore implements RouteStore {

    private static final String HASH_KEY = "gateway:route-catalog";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisRouteStore(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<List<RouteConfig>> load() {
        return redis.<String, String>opsForHash().values(HASH_KEY)
                .flatMap(json -> Mono.fromCallable(() -> objectMapper.readValue(json, RouteConfig.class)))
                .collectList();
    }

    @Override
    public Mono<Void> save(RouteConfig route) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(route))
                .onErrorMap(JacksonException.class,
                        exception -> new IllegalStateException("Could not serialize route configuration.", exception))
                .flatMap(json -> redis.<String, String>opsForHash().put(HASH_KEY, route.id(), json))
                .then();
    }

    @Override
    public Mono<Void> delete(String serviceId) {
        return redis.<String, String>opsForHash().remove(HASH_KEY, serviceId).then();
    }
}
