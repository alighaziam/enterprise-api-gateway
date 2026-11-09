package com.alighazi.gateway.config;

import java.security.Principal;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    KeyResolver principalKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> !name.isBlank())
                .defaultIfEmpty("anonymous");
    }
}