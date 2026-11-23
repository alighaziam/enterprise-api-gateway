package com.alighazi.gateway.web;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.RestController;

import com.alighazi.gateway.ops.RouteAuditService;
import com.alighazi.gateway.ops.RouteAuditStore;
import com.alighazi.gateway.ops.RouteCatalog;
import com.alighazi.gateway.ops.RouteGovernance;
import com.alighazi.gateway.web.ConsoleStatusService.ConsoleSnapshot;

import reactor.core.publisher.Mono;

@RestController
public class ConsoleController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleController.class);

    private final ConsoleStatusService statusService;
    private final RouteCatalog catalog;
    private final RouteAuditService audit;

    public ConsoleController(ConsoleStatusService statusService, RouteCatalog catalog, RouteAuditService audit) {
        this.statusService = statusService;
        this.catalog = catalog;
        this.audit = audit;
    }

    @GetMapping("/api/console/status")
    public Mono<ConsoleSnapshot> status() {
        return statusService.snapshot();
    }

    @GetMapping("/api/console/audit")
    public Mono<ResponseEntity<Object>> audit(@RequestParam(defaultValue = "50") Integer limit) {
        return audit.latest(limit)
                .map(entries -> ResponseEntity.ok().body((Object) entries))
                .onErrorResume(IllegalArgumentException.class, error ->
                        Mono.just(ResponseEntity.badRequest().body((Object) Map.of("error", error.getMessage()))));
    }

    @PostMapping("/api/console/routes")
    public Mono<ResponseEntity<Object>> create(
            @RequestBody RouteDraft draft,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        int replenish = draft.replenishPerSecond() == null ? 10 : draft.replenishPerSecond();
        int burst = draft.burst() == null ? Math.max(20, replenish) : draft.burst();
        boolean enabled = draft.enabled() == null || draft.enabled();
        String actor = actor(jwt);
        String correlationId = correlationId(exchange);
        return catalog.create(draft.id(), draft.backend(), draft.scope(), replenish, burst, enabled)
                .flatMap(created -> auditSafely(actor, "CREATE", draft.id(), "SUCCEEDED", "", correlationId)
                        .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body((Object) created)))
                .onErrorResume(error -> auditSafely(actor, "CREATE", draft.id(), "FAILED", reason(error), correlationId)
                        .then(errorResponse(error)));
    }

    @PostMapping("/api/console/routes/{serviceId}")
    public Mono<ResponseEntity<Object>> update(
            @PathVariable String serviceId,
            @RequestBody RouteUpdate update,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        String actor = actor(jwt);
        String correlationId = correlationId(exchange);
        return catalog.update(
                        serviceId,
                        update.enabled(),
                        update.replenishPerSecond(),
                        update.burst(),
                        update.backend(),
                        update.scope())
                .flatMap(updated -> auditSafely(actor, "UPDATE", serviceId, "SUCCEEDED", "", correlationId)
                        .thenReturn(ResponseEntity.ok().body((Object) updated)))
                .onErrorResume(error -> auditSafely(actor, "UPDATE", serviceId, "FAILED", reason(error), correlationId)
                        .then(errorResponse(error)));
    }

    @DeleteMapping("/api/console/routes/{serviceId}")
    public Mono<ResponseEntity<Object>> delete(
            @PathVariable String serviceId,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        String actor = actor(jwt);
        String correlationId = correlationId(exchange);
        return catalog.delete(serviceId)
                .then(auditSafely(actor, "DELETE", serviceId, "SUCCEEDED", "", correlationId))
                .then(Mono.fromSupplier(() -> ResponseEntity.noContent().<Object>build()))
                .onErrorResume(error -> auditSafely(actor, "DELETE", serviceId, "FAILED", reason(error), correlationId)
                        .then(errorResponse(error)));
    }

    private Mono<Void> auditSafely(String actor, String action, String serviceId,
                                   String outcome, String reason, String correlationId) {
        return Mono.defer(() -> audit.record(actor, action, serviceId, outcome, reason, correlationId))
                .onErrorResume(error -> {
                    log.warn("Could not persist route audit event: action={} serviceId={}", action, serviceId);
                    return Mono.empty();
                });
    }

    private static String actor(Jwt jwt) {
        return jwt == null ? "unknown" : jwt.getSubject();
    }

    private static String correlationId(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
    }

    private static String reason(Throwable error) {
        if (error instanceof RouteGovernance.UnknownService) {
            return "unknown_service";
        }
        if (error instanceof IllegalArgumentException illegal && illegal.getMessage() != null) {
            return illegal.getMessage();
        }
        return "operation_failed";
    }

    private Mono<ResponseEntity<Object>> errorResponse(Throwable error) {
        if (error instanceof RouteGovernance.UnknownService) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown_service")));
        }
        if (error instanceof IllegalArgumentException illegal) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", illegal.getMessage())));
        }
        return Mono.error(error);
    }

    public record RouteDraft(
            String id, String backend, String scope, Boolean enabled, Integer replenishPerSecond, Integer burst) {
    }

    public record RouteUpdate(
            Boolean enabled, Integer replenishPerSecond, Integer burst, String backend, String scope) {
    }
}
