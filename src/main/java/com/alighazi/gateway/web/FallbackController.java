package com.alighazi.gateway.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    @GetMapping({"/fallback/orders", "/fallback/customers"})
    public ResponseEntity<Map<String, Object>> serviceUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Service temporarily unavailable",
                "message", "The downstream service is currently unavailable. Please retry later."
        ));
    }
}