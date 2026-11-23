package com.alighazi.gateway.ops;

import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/** Redis-backed audit list; newest entries are kept first and storage is capped. */
@Component
public class RedisRouteAuditStore implements RouteAuditStore {

    private static final String LIST_KEY = "gateway:route-audit";
    private static final long MAX_ENTRIES = 200;

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisRouteAuditStore(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> append(Entry entry) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(entry))
                .onErrorMap(JacksonException.class,
                        exception -> new IllegalStateException("Could not serialize route audit event.", exception))
                .flatMap(json -> redis.opsForList().leftPush(LIST_KEY, json))
                .then(redis.opsForList().trim(LIST_KEY, 0, MAX_ENTRIES - 1))
                .then();
    }

    @Override
    public Mono<List<Entry>> latest(int limit) {
        if (limit < 1 || limit > MAX_ENTRIES) {
            return Mono.error(new IllegalArgumentException("invalid_audit_limit"));
        }
        return redis.opsForList().range(LIST_KEY, 0, limit - 1)
                .concatMap(json -> Mono.fromCallable(() -> objectMapper.readValue(json, Entry.class))
                        .onErrorMap(JacksonException.class,
                                exception -> new IllegalStateException("Could not read route audit event.", exception)))
                .collectList();
    }
}
