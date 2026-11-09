package com.alighazi.gateway.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void resolvesAuthenticatedPrincipalAsRateLimitKey() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.just((Principal) () -> "client-123"));

        StepVerifier.create(config.principalKeyResolver().resolve(exchange))
                .expectNext("client-123")
                .verifyComplete();
    }

    @Test
    void usesAnonymousKeyWhenPrincipalIsMissing() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.empty());

        StepVerifier.create(config.principalKeyResolver().resolve(exchange))
                .expectNext("anonymous")
                .verifyComplete();
    }
}