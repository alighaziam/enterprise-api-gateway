package com.alighazi.gateway.ops;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

@Service
public class RouteAuditService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> ACTIONS = Set.of("CREATE", "UPDATE", "DELETE");
    private static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED");
    private static final Pattern SAFE_CODE = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final RouteAuditStore store;

    public RouteAuditService(RouteAuditStore store) {
        this.store = store;
    }

    public Mono<Void> record(String actor, String action, String serviceId,
                             String outcome, String reason, String correlationId) {
        return Mono.defer(() -> {
            String normalizedAction = normalize(action, ACTIONS, "invalid_audit_action");
            String normalizedOutcome = normalize(outcome, OUTCOMES, "invalid_audit_outcome");
            RouteAuditStore.Entry entry = new RouteAuditStore.Entry(
                    UUID.randomUUID().toString(),
                    boundedActor(actor),
                    normalizedAction,
                    boundedCode(serviceId, "unknown"),
                    normalizedOutcome,
                    boundedReason(reason),
                    boundedCorrelation(correlationId),
                    Instant.now().toString());
            return store.append(entry);
        });
    }

    public Mono<List<RouteAuditStore.Entry>> latest(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            return Mono.error(new IllegalArgumentException("invalid_audit_limit"));
        }
        return store.latest(limit);
    }

    private static String normalize(String value, Set<String> allowed, String errorCode) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(errorCode);
        }
        return normalized;
    }

    private static String boundedActor(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String compact = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (compact.isEmpty()) {
            return "unknown";
        }
        return compact.length() > 80 ? compact.substring(0, 80) : compact;
    }

    private static String boundedCode(String value, String fallback) {
        if (value == null || !SAFE_CODE.matcher(value).matches()) {
            return fallback;
        }
        return value;
    }

    private static String boundedReason(String value) {
        return boundedCode(value, "");
    }

    private static String boundedCorrelation(String value) {
        return value != null && SAFE_CORRELATION.matcher(value).matches() ? value : "";
    }
}
