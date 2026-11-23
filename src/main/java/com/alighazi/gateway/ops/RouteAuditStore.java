package com.alighazi.gateway.ops;

import java.util.List;

import reactor.core.publisher.Mono;

/** Durable, bounded history of operator-managed route changes. */
public interface RouteAuditStore {

    Mono<Void> append(Entry entry);

    Mono<List<Entry>> latest(int limit);

    record Entry(
            String id,
            String actor,
            String action,
            String serviceId,
            String outcome,
            String reason,
            String correlationId,
            String occurredAt) {
    }
}
