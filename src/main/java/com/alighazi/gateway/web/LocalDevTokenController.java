package com.alighazi.gateway.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alighazi.gateway.web.LocalDevTokenService.DevToken;

@RestController
public class LocalDevTokenController {

    private final boolean enabled;
    private final LocalDevTokenService tokens;

    public LocalDevTokenController(
            @Value("${local-dev.tokens-enabled:false}") boolean enabled,
            LocalDevTokenService tokens) {
        this.enabled = enabled;
        this.tokens = tokens;
    }

    @GetMapping("/jwks")
    public ResponseEntity<Map<String, Object>> jwks() {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(tokens.publicJwkSet());
    }

    @GetMapping(value = "/api/console/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> token(@RequestParam(name = "scope", defaultValue = "orders.read customers.read") String scope) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Local development tokens are disabled"));
        }
        try {
            DevToken issued = tokens.issue(scope);
            return ResponseEntity.ok(issued);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
