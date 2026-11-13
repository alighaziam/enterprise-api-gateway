package com.alighazi.gateway.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.alighazi.gateway.ops.RouteCatalog;
import com.alighazi.gateway.ops.RouteGovernance;
import com.alighazi.gateway.web.ConsoleStatusService.ConsoleSnapshot;

import reactor.core.publisher.Mono;

@RestController
public class ConsoleController {

    private final ConsoleStatusService statusService;
    private final RouteCatalog catalog;

    public ConsoleController(ConsoleStatusService statusService, RouteCatalog catalog) {
        this.statusService = statusService;
        this.catalog = catalog;
    }

    @GetMapping("/api/console/status")
    public Mono<ConsoleSnapshot> status() {
        return statusService.snapshot();
    }

    @PostMapping("/api/console/routes")
    public Mono<ResponseEntity<Object>> create(@RequestBody RouteDraft draft) {
        int replenish = draft.replenishPerSecond() == null ? 10 : draft.replenishPerSecond();
        int burst = draft.burst() == null ? Math.max(20, replenish) : draft.burst();
        boolean enabled = draft.enabled() == null || draft.enabled();
        return catalog.create(draft.id(), draft.backend(), draft.scope(), replenish, burst, enabled)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body((Object) created))
                .onErrorResume(this::errorResponse);
    }

    @PostMapping("/api/console/routes/{serviceId}")
    public Mono<ResponseEntity<Object>> update(@PathVariable String serviceId, @RequestBody RouteUpdate update) {
        return catalog.update(
                        serviceId,
                        update.enabled(),
                        update.replenishPerSecond(),
                        update.burst(),
                        update.backend(),
                        update.scope())
                .map(updated -> ResponseEntity.ok().body((Object) updated))
                .onErrorResume(this::errorResponse);
    }

    @DeleteMapping("/api/console/routes/{serviceId}")
    public Mono<ResponseEntity<Object>> delete(@PathVariable String serviceId) {
        return catalog.delete(serviceId)
                .then(Mono.fromSupplier(() -> ResponseEntity.noContent().<Object>build()))
                .onErrorResume(this::errorResponse);
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
