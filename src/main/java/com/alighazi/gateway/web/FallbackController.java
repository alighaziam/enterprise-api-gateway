package com.alighazi.gateway.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    @RequestMapping(
            path = "/fallback/{serviceId}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS})
    public ResponseEntity<Map<String, Object>> serviceUnavailable(@PathVariable String serviceId) {
        return response(serviceId);
    }

    public ResponseEntity<Map<String, Object>> serviceUnavailable() {
        return response("unknown");
    }

    private ResponseEntity<Map<String, Object>> response(String serviceId) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "service", serviceId,
                "error", "Service temporarily unavailable",
                "message", "The downstream service is currently unavailable. Please retry later."
        ));
    }
}
