package com.alighazi.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.alighazi.gateway.ops.RouteGovernance;
import com.alighazi.gateway.ops.RouteGovernance.ServiceOperations;
import com.alighazi.gateway.web.ConsoleStatusService.ConsoleSnapshot;

import reactor.core.publisher.Mono;

@RestController
public class ConsoleController {

    private final ConsoleStatusService statusService;
    private final RouteGovernance governance;

    public ConsoleController(ConsoleStatusService statusService, RouteGovernance governance) {
        this.statusService = statusService;
        this.governance = governance;
    }

    @GetMapping("/api/console/status")
    public Mono<ConsoleSnapshot> status() {
        return statusService.snapshot();
    }

    @PostMapping("/api/console/routes/{serviceId}")
    public ResponseEntity<?> update(@PathVariable String serviceId, @RequestBody RouteUpdate update) {
        try {
            ServiceOperations updated = governance.update(
                    serviceId, update.enabled(), update.replenishPerSecond(), update.burst());
            return ResponseEntity.ok(updated);
        } catch (RouteGovernance.UnknownService ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("error", "unknown_service"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    public record RouteUpdate(Boolean enabled, Integer replenishPerSecond, Integer burst) {
    }
}
